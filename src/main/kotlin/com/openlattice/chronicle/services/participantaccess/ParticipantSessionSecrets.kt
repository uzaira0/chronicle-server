package com.openlattice.chronicle.services.participantaccess

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import com.openlattice.chronicle.util.hmacSha256

/** Reconstructs an exact exchange response without storing a recoverable credential. */
internal object ParticipantSessionSecrets {
    private val secureRandom = SecureRandom()
    private const val TOKEN_BYTES = 48
    private const val MAX_TOKEN_CHARS = 256

    fun sessionToken(accessCode: String, attemptId: UUID, key: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(hmacSha256(key, "session\u0000$accessCode\u0000$attemptId"))

    fun csrfToken(sessionToken: String): String = digest("csrf\u0000$sessionToken")

    /**
     * A researcher's enrollment invitation is derived from its row id, so the dashboard can show the link it already
     * sent without the raw code ever being stored.
     */
    fun enrollmentInvitationCode(accessCodeId: UUID, key: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(hmacSha256(key, "enrollment-invitation\u0000$accessCodeId"))

    private fun digest(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(value))

    fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))

    fun isPlausibleToken(value: String?): Boolean =
        value != null && value.length in 32..MAX_TOKEN_CHARS && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

}
