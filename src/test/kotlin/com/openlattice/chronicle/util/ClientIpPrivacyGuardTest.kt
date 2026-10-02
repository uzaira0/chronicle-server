package com.openlattice.chronicle.util

import com.openlattice.chronicle.audit.AuditAction
import com.openlattice.chronicle.audit.AuditLogEntry
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.filters.MobileApiHmacAuthenticationToken
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Participants' IP addresses are never logged or stored; staff IPs only as keyed references.
 * The source scan fails the build when new code reads a client address outside [ClientIpRecord].
 */
class ClientIpPrivacyGuardTest {
    private val ip = "198.51.100.23"
    private fun request() = MockHttpServletRequest().apply { remoteAddr = ip }

    @After
    fun reset() {
        SecurityContextHolder.clearContext()
        ClientIpRecord.recordStaffIp = false
    }

    @Test
    fun `participant and anonymous requests get no ip reference`() {
        ClientIpRecord.recordStaffIp = true
        assertNull(ClientIpRecord.staffReference(request()))
        assertEquals(ClientIpRecord.WITHHELD, ClientIpRecord.logReference(request()))

        SecurityContextHolder.getContext().authentication = MobileApiHmacAuthenticationToken(UUID.randomUUID())
        assertNull(ClientIpRecord.staffReference(request()))
    }

    @Test
    fun `staff ip is withheld unless the operator turns recording on`() {
        TestSecurityUtils.setupSecurityContext()
        assertNull(ClientIpRecord.staffReference(request()))
        assertEquals(ClientIpRecord.WITHHELD, ClientIpRecord.staffSurfaceReference(request()))
        assertEquals(ClientIpRecord.WITHHELD, ClientIpRecord.logReference(request()))
    }

    @Test
    fun `staff requests get a keyed reference, not a plain hash`() {
        ClientIpRecord.recordStaffIp = true
        TestSecurityUtils.setupSecurityContext()
        val reference = ClientIpRecord.staffReference(request())!!

        assertTrue(ClientIpRecord.isReference(reference))
        assertFalse(reference.contains(ip))
        val plain = MessageDigest.getInstance("SHA-256").digest(ip.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(12)
        assertNotEquals("ip:$plain", reference)
    }

    @Test
    fun `fingerprints depend on the key`() {
        val deployment = LogSanitizer.fingerprintKey("deployment-secret")
        assertTrue(deployment.contentEquals(LogSanitizer.fingerprintKey("deployment-secret")))
        assertNotEquals(
            LogSanitizer.keyedFingerprint(deployment, "participant-1", "participant"),
            LogSanitizer.keyedFingerprint(LogSanitizer.fingerprintKey("other-secret"), "participant-1", "participant"),
        )
        assertFalse(LogSanitizer.fingerprintKey(null).contentEquals(LogSanitizer.fingerprintKey(null)))
    }

    @Test
    fun `audit persistence keeps only keyed references`() {
        fun persisted(ipAddress: String) = AuditService.sanitizeForPersistence(
            AuditLogEntry(ipAddress = ipAddress, action = AuditAction.VIEW, resourceType = "Test", success = true)
        ).ipAddress

        assertEquals(ClientIpRecord.WITHHELD, persisted(ip))
        assertEquals(ClientIpRecord.WITHHELD, persisted("ip:[withheld]"))
        assertEquals("ip:0123456789ab", persisted("ip:0123456789ab"))
    }

    @Test
    fun `client addresses are read only through ClientIpRecord`() {
        val rules = mapOf(
            Regex("""ClientIpResolver\.resolve""") to setOf("ClientIpResolver.kt", "ClientIpRecord.kt", "RateLimitFilter.kt"),
            Regex("""\bremoteAddr\b|getRemoteAddr|getRemoteHost|\bremoteHost\b""") to setOf("ClientIpResolver.kt"),
            Regex("""staffSurfaceReference\(""") to
                setOf("ClientIpRecord.kt", "AuthTokenController.kt", "ApiKeyAuthenticationFilter.kt"),
            Regex(""""clientIp"""") to emptySet(),
        )
        val violations = listOf(File("src/main/kotlin"), File("src/main/java"), File("src/main/resources"))
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile } }
            .flatMap { file ->
                val text = file.readText()
                rules.filter { (pattern, allowed) -> file.name !in allowed && pattern.containsMatchIn(text) }
                    .map { (pattern, _) -> "${file.path}: ${pattern.pattern}" }
            }
        assertTrue(
            "Client IP read outside ClientIpRecord; route it through ClientIpRecord.staffReference:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }
}
