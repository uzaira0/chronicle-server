package com.openlattice.chronicle.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChronicleAuthConfigurationTest {
    private val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    @Test
    fun invalidSessionIdleValuesNameTheOperatorEnvironmentSetting() {
        for (minutes in listOf(0L, 121L)) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                ChronicleAuthConfiguration(sessionIdleMinutes = minutes)
            }
            assertTrue(failure.message.orEmpty().contains(SESSION_IDLE_MINUTES_ENV))
        }
        assertEquals(1L, ChronicleAuthConfiguration(sessionIdleMinutes = 1).sessionIdleMinutes)
        assertEquals(120L, ChronicleAuthConfiguration(sessionIdleMinutes = 120).sessionIdleMinutes)
        for (value in listOf("", "three", "1.5")) {
            val failure = assertThrows(IllegalArgumentException::class.java) { parseSessionIdleMinutes(value) }
            assertTrue(failure.message.orEmpty().contains(SESSION_IDLE_MINUTES_ENV))
        }
    }

    @Test
    fun `deserializes snake_case testing user metadata fields`() {
        val rawConfig = """
            {
              "testingLoginEnabled": true,
              "users": [
                {
                  "user_id": "local-admin",
                  "email": "admin@chronicle.local",
                  "email_verified": true,
                  "app_metadata": {
                    "roles": ["AuthenticatedUser", "admin"]
                  }
                }
              ]
            }
        """.trimIndent()

        val config = objectMapper.readValue<ChronicleAuthConfiguration>(rawConfig)
        val configuredUser = config.users.single()

        assertEquals(true, configuredUser.emailVerified)
        val roles = configuredUser.appMetadata["roles"]
        assertNotNull(roles)
        @Suppress("UNCHECKED_CAST")
        val roleList = roles as List<String>
        assertEquals(listOf("AuthenticatedUser", "admin"), roleList)
    }
}
