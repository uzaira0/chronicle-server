package com.openlattice.chronicle.configuration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.test.util.ReflectionTestUtils

class MobileApiSecurityConfigTest {

    @Test
    fun `public defaults keep legacy signing and the internal bypass disabled`() {
        val configuration = MobileSecurityConfiguration()

        assertFalse(configuration.enabled)
        assertFalse(configuration.signingRequired)
        assertTrue(configuration.signingSecret.isBlank())
        assertTrue(configuration.internalWebSecret.isBlank())
    }

    @Test
    fun `enforced legacy signing requires an internal web proxy secret`() {
        val subject = MobileApiSecurityConfig()
        ReflectionTestUtils.setField(
            subject,
            "mobileSecurityConfiguration",
            MobileSecurityConfiguration(
                enabled = true,
                signingSecret = "controlled-legacy-signing-secret-32-bytes!!",
                signingRequired = true,
                internalWebSecret = "",
            ),
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            subject.mobileApiSignatureFilter()
        }

        assertTrue(failure.message.orEmpty().contains("internal-web-secret is blank"))
    }
}
