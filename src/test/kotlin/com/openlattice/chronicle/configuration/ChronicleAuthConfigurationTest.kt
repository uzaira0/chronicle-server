package com.openlattice.chronicle.configuration

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ChronicleAuthConfigurationTest {
    private val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

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
