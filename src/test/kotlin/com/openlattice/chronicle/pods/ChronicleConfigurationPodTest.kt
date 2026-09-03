package com.openlattice.chronicle.pods

import com.geekbeast.rhizome.pods.ConfigurationLoader
import com.openlattice.chronicle.configuration.MobileSecurityConfiguration
import com.openlattice.chronicle.configuration.RateLimitConfiguration
import org.junit.Assert.assertEquals
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

        val pod = ChronicleConfigurationPod()
        setField(pod, "configurationLoader", loader)

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

        val pod = ChronicleConfigurationPod()
        setField(pod, "configurationLoader", loader)

        assertEquals(expected, pod.rateLimitConfiguration())
        verify(loader).logAndLoad("Rate Limit Configuration", RateLimitConfiguration::class.java)
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }
}
