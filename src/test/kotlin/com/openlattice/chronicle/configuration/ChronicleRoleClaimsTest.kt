package com.openlattice.chronicle.configuration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChronicleRoleClaimsTest {
    @Test
    fun `public server default is generic and extracts its namespaced roles`() {
        val configuration = ChronicleAuthConfiguration()
        val roles = ChronicleRoleClaims.extract(
            mapOf(configuration.roleClaimNamespace to mapOf("roles" to listOf("admin"))),
            configuration.roleClaimNamespace,
            configuredClientId = null,
        )

        assertFalse(configuration.roleClaimNamespace.contains("bcm", ignoreCase = true))
        assertEquals(listOf("admin"), roles)
    }

    @Test
    fun `operator configured namespace is authoritative`() {
        val namespace = "https://research.example.org/chronicle/metadata"

        assertEquals(
            listOf("researcher"),
            ChronicleRoleClaims.extract(
                mapOf(namespace to mapOf("roles" to listOf("researcher"))),
                namespace,
                configuredClientId = null,
            ),
        )
    }

    @Test
    fun `unrelated client roles cannot become Chronicle roles`() {
        val issuer = "https://id.example.org/realms/research"
        val configuration = ChronicleAuthConfiguration(
            oidc = ChronicleOidcConfiguration(
                enabled = true,
                issuer = issuer,
                clientId = "chronicle-dashboard",
            ),
        )
        val claims = mapOf<String, Any>(
            "iss" to issuer,
            "aud" to listOf("chronicle-dashboard"),
            "resource_access" to mapOf(
                "unrelated-admin-console" to mapOf("roles" to listOf("ADMIN")),
                "chronicle-dashboard" to mapOf("roles" to listOf("researcher")),
            ),
        )

        val clientId = configuration.roleClientIdForClaims(claims)
        val roles = ChronicleRoleClaims.extract(
            claims,
            configuration.roleClaimNamespace,
            clientId,
        )

        assertEquals("chronicle-dashboard", clientId)
        assertEquals(listOf("researcher"), roles)
        assertFalse(roles.contains("ADMIN"))
    }
}
