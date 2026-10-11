package com.openlattice.chronicle.services.receipts

import com.openlattice.chronicle.services.participantaccess.IdempotentSubmissionResult
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.StorageResolver
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class CreateReceiptResponse(val status: Int, val contentType: String?, val body: ByteArray)

internal class CreateReceiptConflictException(override val message: String) : RuntimeException(message)

/** The receipt and mutation commit together before the HTTP result is sent. */
internal class ResearcherCreateReceiptService(private val storage: StorageResolver) {
    // Any mutation or persistence failure must roll back the receipt transaction and propagate.
    @Suppress("TooGenericExceptionCaught")
    fun execute(
        principal: String,
        route: String,
        key: String,
        body: ByteArray,
        encryptionSecret: String,
        study: UUID?,
        action: () -> CreateReceiptResponse,
    ): IdempotentSubmissionResult<CreateReceiptResponse> {
        val hash = digest(body)
        val scope = "$principal\u0000$route\u0000$key"
        val pool = storage.getPlatformStorage()
        return pool.connection.use { connection ->
            connection.autoCommit = false
            try {
                val inserted = connection.prepareStatement("""
                    INSERT INTO researcher_create_receipts (principal_id, route, idempotency_key, request_hash)
                    VALUES (?, ?, ?, ?) ON CONFLICT (principal_id, route, idempotency_key) DO NOTHING
                """.trimIndent()).use { statement ->
                    statement.setString(1, principal); statement.setString(2, route)
                    statement.setString(3, key); statement.setBytes(4, hash)
                    statement.executeUpdate() == 1
                }
                if (!inserted) {
                    val replay = loadReplay(connection, principal, route, key, hash, encryptionSecret, scope)
                    connection.rollback()
                    return@use IdempotentSubmissionResult(replay, replayed = true)
                }
                val result = try {
                    PinnedPlatformConnection.committing(pool, connection) {
                        val response = action()
                        if (response.status >= 400) throw FailedCreateResponse(response)
                        val sealed = seal(response.body, encryptionSecret, scope)
                        persistResponse(connection, principal, route, key, study, response, sealed)
                        response
                    }
                } catch (failure: FailedCreateResponse) {
                    connection.rollback()
                    failure.response
                }
                IdempotentSubmissionResult(result, replayed = false)
            } catch (failure: Exception) { connection.rollback(); throw failure }
        }
    }

    private fun loadReplay(
        connection: Connection, principal: String, route: String, key: String,
        hash: ByteArray, encryptionSecret: String, scope: String,
    ): CreateReceiptResponse {
        return connection.prepareStatement("""
            SELECT request_hash, response_status, response_type, sealed_response
            FROM researcher_create_receipts WHERE principal_id = ? AND route = ? AND idempotency_key = ?
            FOR UPDATE
        """.trimIndent()).use { statement ->
            statement.setString(1, principal); statement.setString(2, route); statement.setString(3, key)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Create receipt is unavailable" }
                if (!MessageDigest.isEqual(hash, rows.getBytes("request_hash"))) {
                    throw CreateReceiptConflictException("Idempotency-Key was already used for a different request")
                }
                val replay = runCatching { open(rows.getString("sealed_response"), encryptionSecret, scope) }.getOrNull()
                    ?: throw CreateReceiptConflictException("This create already completed; its original result cannot be replayed")
                CreateReceiptResponse(rows.getInt("response_status"), rows.getString("response_type"), replay)
            }
        }
    }

    private fun persistResponse(
        connection: Connection, principal: String, route: String, key: String,
        study: UUID?, response: CreateReceiptResponse, sealed: String,
    ) {
        connection.prepareStatement("""
            UPDATE researcher_create_receipts SET response_status = ?, response_type = ?, sealed_response = ?, study_id = ?
            WHERE principal_id = ? AND route = ? AND idempotency_key = ?
        """.trimIndent()).use { statement ->
            statement.setInt(1, response.status); statement.setString(2, response.contentType ?: "application/json")
            statement.setString(3, sealed)
            val receiptStudy = study ?: runCatching {
                UUID.fromString(response.body.toString(Charsets.UTF_8).trim().trim('"'))
            }.getOrNull()
            statement.setObject(4, receiptStudy); statement.setString(5, principal)
            statement.setString(6, route); statement.setString(7, key)
            check(statement.executeUpdate() == 1) { "Create receipt was not persisted" }
        }
    }

    private class FailedCreateResponse(val response: CreateReceiptResponse) : RuntimeException()

    private val random = SecureRandom()

    private fun seal(body: ByteArray, secret: String, scope: String): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, secret, nonce, scope)
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(body))
    }

    private fun open(sealed: String, secret: String, scope: String): ByteArray {
        val bytes = Base64.getDecoder().decode(sealed)
        return cipher(Cipher.DECRYPT_MODE, secret, bytes.copyOfRange(0, 12), scope).doFinal(bytes.copyOfRange(12, bytes.size))
    }

    private fun cipher(mode: Int, secret: String, nonce: ByteArray, scope: String): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(digest(secret.toByteArray(Charsets.UTF_8)), "AES"), GCMParameterSpec(128, nonce))
        updateAAD(scope.toByteArray(Charsets.UTF_8))
    }

    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
