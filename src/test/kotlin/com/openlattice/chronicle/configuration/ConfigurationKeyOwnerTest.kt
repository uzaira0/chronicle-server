package com.openlattice.chronicle.configuration

import com.geekbeast.rhizome.configuration.Configuration
import com.geekbeast.rhizome.configuration.ConfigurationKey
import com.geekbeast.mappers.mappers.ObjectMappers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ConfigurationKeyOwnerTest {
    @Test fun accessorsReturnTheirCompanionOwnerWithoutRecursion() {
        val owners = mapOf<Configuration, ConfigurationKey>(
            MobileSecurityConfiguration() to MobileSecurityConfiguration.key,
            RateLimitConfiguration() to RateLimitConfiguration.key,
            ChronicleAuthConfiguration() to ChronicleAuthConfiguration.key,
            VaultConfiguration() to VaultConfiguration.key,
            SsrfConfig() to SsrfConfig.key,
            CorsConfiguration() to CorsConfiguration.key,
        )
        owners.forEach { (configuration, expected) -> assertSame(expected, configuration.key) }
        val mobile = owners.keys.filterIsInstance<MobileSecurityConfiguration>().single()
        val mapper = ObjectMappers.getJsonMapper()
        assertEquals(mobile, mapper.readValue(mapper.writeValueAsString(mobile), mobile.javaClass))
    }
}
