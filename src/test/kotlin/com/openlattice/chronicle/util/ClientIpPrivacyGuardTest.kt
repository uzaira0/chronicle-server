package com.openlattice.chronicle.util

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
import java.security.MessageDigest
import java.util.UUID

/**
 * Participants' IP addresses are never logged or stored; staff IPs only as keyed references.
 * lint/privacy (ast-grep) keeps every client-address read inside [ClientIpRecord] and its resolver.
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
    fun `staff ip is withheld when the operator turns recording off`() {
        ClientIpRecord.recordStaffIp = false
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
        fun persisted(ipAddress: String) = com.openlattice.chronicle.auditing.AuditRequest.of(
            MockHttpServletRequest("GET", "/chronicle/v3/study"), ipAddress,
        ).clientIpRef

        assertEquals(ClientIpRecord.WITHHELD, persisted(ip))
        assertEquals(ClientIpRecord.WITHHELD, persisted("ip:[withheld]"))
        assertEquals("ip:0123456789ab", persisted("ip:0123456789ab"))
    }
}
