package com.openlattice.chronicle.pods

import com.geekbeast.rhizome.pods.ConfigurationLoader
import com.openlattice.chronicle.configuration.MobileSecurityConfiguration
import com.openlattice.chronicle.configuration.RateLimitConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify

class ChronicleConfigurationPodTest {

    @Test
    fun testMobileSecurityConfigurationIsLoadedFromConfigurationLoader() {
        val loader = Mockito.mock(ConfigurationLoader::class.java)
        val expected = MobileSecurityConfiguration(
            enabled = true,
            signingSecret = "0123456789abcdef0123456789abcdef",
            signingRequired = true
        )
        `when`(
            loader.logAndLoad(
                "Mobile Security Configuration",
                MobileSecurityConfiguration::class.java
            )
        ).thenReturn(expected)

        val pod = ChronicleConfigurationPod(loader)

        assertEquals(expected, pod.mobileSecurityConfiguration())
        verify(loader).logAndLoad("Mobile Security Configuration", MobileSecurityConfiguration::class.java)
    }

    @Test
    fun testRateLimitConfigurationIsLoadedFromConfigurationLoader() {
        val loader = Mockito.mock(ConfigurationLoader::class.java)
        val expected = RateLimitConfiguration(enabled = true)
        `when`(
            loader.logAndLoad(
                "Rate Limit Configuration",
                RateLimitConfiguration::class.java
            )
        ).thenReturn(expected)

        val pod = ChronicleConfigurationPod(loader)

        assertEquals(expected, pod.rateLimitConfiguration())
        verify(loader).logAndLoad("Rate Limit Configuration", RateLimitConfiguration::class.java)
    }

    /**
     * An operator whose bundle lacks the optional vault, CORS or error-sanitization file (or ships one that does
     * not parse) still gets a running server with Vault off and the secure defaults, never a half-read config.
     */
    @Test
    fun missingOptionalConfigurationFilesFallBackToSecureDefaults() {
        val loader = Mockito.mock(ConfigurationLoader::class.java)
        `when`(loader.logAndLoad(Mockito.anyString(), org.mockito.kotlin.any<Class<Any>>()))
            .thenThrow(IllegalStateException("config file not found"))
        val pod = ChronicleConfigurationPod(loader)

        assertEquals(com.openlattice.chronicle.configuration.VaultConfiguration(), pod.vaultConfiguration())
        assertFalse(pod.vaultConfiguration().enabled)
        assertEquals(com.openlattice.chronicle.configuration.CorsConfiguration(), pod.corsConfiguration())
        val sanitization = pod.errorSanitizationConfig()
        assertEquals(com.openlattice.chronicle.configuration.ErrorSanitizationConfig(), sanitization)
        assertTrue(sanitization.sanitizeErrors)
        assertFalse(sanitization.includeStackTrace)
    }

    /** An error-sanitization file that exposes details is honoured as written (the operator chose it), not replaced. */
    @Test
    fun unsafeErrorSanitizationFileIsLoadedAsWritten() {
        val loader = Mockito.mock(ConfigurationLoader::class.java)
        val unsafe = com.openlattice.chronicle.configuration.ErrorSanitizationConfig(
            sanitizeErrors = false, includeStackTrace = true, includeErrorId = false, logFullErrors = false,
        )
        `when`(loader.logAndLoad("Error Sanitization Configuration",
            com.openlattice.chronicle.configuration.ErrorSanitizationConfig::class.java)).thenReturn(unsafe)

        assertEquals(4, unsafe.validate().size)
        assertEquals(unsafe, ChronicleConfigurationPod(loader).errorSanitizationConfig())
    }
}
