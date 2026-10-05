package com.openlattice.chronicle.filters

import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.StorageResolver
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Unique durable receipts and the mutation share one transaction, including lost HTTP responses. */
internal class CreateReceiptFilter(
    private val storage: StorageResolver,
    private val authorization: StudyAuthorizationService,
    private val secret: () -> String? = { System.getenv("CHRONICLE_INTERNAL_WEB_SECRET") },
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        guarded(request, response, chain)
    }

    internal fun guarded(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val route = request.requestURI.removePrefix("/chronicle").trimEnd('/')
        if (request.method != "POST" || !isCreate(route)) return chain.doFilter(request, response)
        val key = request.getHeader("Idempotency-Key") ?: return chain.doFilter(request, response)
        if (key.length !in 1..128 || !key.matches(Regex("[A-Za-z0-9_-]+"))) {
            error(response, 400, "Idempotency-Key must contain 1–128 letters, digits, underscores or hyphens")
            return
        }
        val encryptionSecret = secret()?.takeIf { it.length >= 32 }
        if (encryptionSecret == null) { error(response, 503, "Create receipt encryption is unavailable"); return }
        val study = STUDY_ROUTE.matchEntire(route)?.groupValues?.get(1)
            ?: SURVEY_ROUTE.matchEntire(route)?.groupValues?.get(1)
        if (study != null) {
            try {
                authorization.requirePermission(UUID.fromString(study),
                    if (route.endsWith("/export/async")) StudyPermission.EXPORT_DATA else StudyPermission.MODIFY_STUDY)
            } catch (denied: com.geekbeast.controllers.exceptions.ForbiddenException) {
                error(response, 403, "Access denied")
                return
            }
        }
        val principal = Principals.getCurrentUser().id
        val body = request.inputStream.readNBytes(MAX_BODY + 1)
        if (body.size > MAX_BODY) { error(response, 413, "Create request is too large"); return }
        val hash = digest(body)
        val pool = storage.getPlatformStorage()
        pool.connection.use { connection ->
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
                    connection.prepareStatement("""
                        SELECT request_hash, response_status, response_type, sealed_response
                        FROM researcher_create_receipts WHERE principal_id = ? AND route = ? AND idempotency_key = ?
                        FOR UPDATE
                    """.trimIndent()).use { statement ->
                        statement.setString(1, principal); statement.setString(2, route); statement.setString(3, key)
                        statement.executeQuery().use { rows ->
                            check(rows.next()) { "Create receipt is unavailable" }
                            if (!MessageDigest.isEqual(hash, rows.getBytes("request_hash"))) {
                                error(response, 409, "Idempotency-Key was already used for a different request")
                            } else {
                                val sealed = rows.getString("sealed_response")
                                val replay = runCatching { open(sealed, encryptionSecret, "$principal\u0000$route\u0000$key") }.getOrNull()
                                if (replay == null) error(response, 409, "This create already completed; its original result cannot be replayed")
                                else {
                                    response.status = rows.getInt("response_status")
                                    response.contentType = rows.getString("response_type")
                                    response.setHeader("Idempotency-Replayed", "true")
                                    response.outputStream.write(replay)
                                }
                            }
                        }
                    }
                    connection.rollback()
                    return
                }
                val cached = ContentCachingResponseWrapper(response)
                try {
                    PinnedPlatformConnection.committing(pool, connection) {
                        chain.doFilter(ReplayableRequest(request, body), cached)
                        if (cached.status >= 400) throw FailedCreateResponse()
                        val sealed = seal(cached.contentAsByteArray, encryptionSecret, "$principal\u0000$route\u0000$key")
                        connection.prepareStatement("""
                            UPDATE researcher_create_receipts SET response_status = ?, response_type = ?, sealed_response = ?, study_id = ?
                            WHERE principal_id = ? AND route = ? AND idempotency_key = ?
                        """.trimIndent()).use { statement ->
                            statement.setInt(1, cached.status); statement.setString(2, cached.contentType ?: "application/json")
                            statement.setString(3, sealed)
                            val receiptStudy = study?.let(UUID::fromString) ?: runCatching {
                                UUID.fromString(cached.contentAsByteArray.toString(Charsets.UTF_8).trim().trim('"'))
                            }.getOrNull()
                            statement.setObject(4, receiptStudy); statement.setString(5, principal)
                            statement.setString(6, route); statement.setString(7, key)
                            check(statement.executeUpdate() == 1) { "Create receipt was not persisted" }
                        }
                    }
                } catch (failure: FailedCreateResponse) {
                    connection.rollback()
                    cached.copyBodyToResponse()
                    return
                }
                // The response is sent only after both mutation and encrypted replay result are durable.
                cached.copyBodyToResponse()
            } catch (failure: Exception) { connection.rollback(); throw failure }
        }
    }

    private class FailedCreateResponse : RuntimeException()

    private class ReplayableRequest(request: HttpServletRequest, private val body: ByteArray) : HttpServletRequestWrapper(request) {
        override fun getInputStream(): ServletInputStream {
            val stream = ByteArrayInputStream(body)
            return object : ServletInputStream() {
                override fun read(): Int = stream.read()
                override fun isFinished(): Boolean = stream.available() == 0
                override fun isReady(): Boolean = true
                override fun setReadListener(listener: ReadListener?) { throw UnsupportedOperationException("Synchronous create request") }
            }
        }
        override fun getReader(): java.io.BufferedReader = getInputStream().bufferedReader(Charsets.UTF_8)
    }

    private fun error(response: HttpServletResponse, status: Int, message: String) {
        response.status = status; response.contentType = "application/json"
        response.writer.write("""{"status":$status,"message":"$message"}""")
    }

    private fun seal(body: ByteArray, secret: String, scope: String): String {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
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

    internal companion object {
        private const val MAX_BODY = 2 * 1024 * 1024
        private val STUDY_ROUTE = Regex("/v3/study/([0-9a-fA-F-]+)/(api-keys|export/async)")
        private val SURVEY_ROUTE = Regex("/v3/survey/([0-9a-fA-F-]+)/questionnaire")
        fun isCreate(route: String): Boolean = route == "/v3/study" || STUDY_ROUTE.matches(route) || SURVEY_ROUTE.matches(route)
    }
}
