package com.openlattice.chronicle.pods.servlet

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.configuration.ChronicleAuthUser
import com.openlattice.chronicle.configuration.ChronicleJwtClientConfiguration
import com.openlattice.chronicle.configuration.JwtKeyMaterial
import com.openlattice.chronicle.configuration.RequestHardeningFilters
import com.openlattice.chronicle.users.ConfiguredUserListingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito
import org.springframework.security.oauth2.jwt.JwtException
import java.time.Instant
import java.util.Base64

class JwtRotationTest {
    private val current = "current-rotation-test-secret-01234567890123456789"
    private val previous = "previous-rotation-test-secret-01234567890123456789"
    private val keys = JwtKeyMaterial("HS256", "boot-id", null, null, null)

    private fun config(expires: Long = Instant.now().epochSecond + 600): ChronicleJwtClientConfiguration =
        ObjectMappers.getJsonMapper().readValue(
            """{"issuer":"https://rotation.test","audience":"dashboard","secret":"$current",
                "previousSecret":"$previous","previousSecretExpiresAt":$expires} """,
            ChronicleJwtClientConfiguration::class.java,
        )

    private fun decoder(config: ChronicleJwtClientConfiguration) = ChronicleServerSecurityPod(
        ChronicleAuthConfiguration(configurations = setOf(config)), keys,
        Mockito.mock(RequestHardeningFilters::class.java), false, "",
    ).jwtDecoder()

    private fun token(secret: String, kid: String? = null, audience: String = "dashboard"): String = JWT.create()
        .withKeyId(kid)
        .withSubject("researcher")
        .withIssuer("https://rotation.test")
        .withAudience(audience)
        .withExpiresAt(Instant.now().plusSeconds(3600))
        .sign(Algorithm.HMAC256(secret))

    @Test fun legacyTokensSurviveRoutineRotationOnlyUntilTheOverlapExpires() {
        assertEquals("researcher", decoder(config()).decode(token(previous)).subject)
        assertThrows(JwtException::class.java) { decoder(config(Instant.now().epochSecond - 1)).decode(token(previous)) }
        assertThrows(JwtException::class.java) { decoder(config(0)).decode(token(previous)) }
        assertEquals("researcher", decoder(config(0)).decode(token(current)).subject)
        assertThrows(JwtException::class.java) { decoder(config()).decode(token(previous, audience = "foreign")) }
        assertThrows(JwtException::class.java) { decoder(config()).decode(token(current, "unknown")) }
    }

    @Test fun issuerUsesStableKeyIdsAndTheVerifierSelectsThePreviousKey() {
        val config = config()
        val auth = ChronicleAuthConfiguration(configurations = setOf(config), users = setOf(
            ChronicleAuthUser(id = "researcher", email = "researcher@rotation.test"),
        ))
        fun issue(client: ChronicleJwtClientConfiguration) = requireNotNull(ConfiguredUserListingService(
            auth.copy(configurations = setOf(client)), keys, 10,
        ).issueDashboardToken("researcher"))
        val issued = issue(config)
        val kid = JWT.decode(issued).keyId
        assertNotNull(kid)
        assertEquals(kid, JWT.decode(issue(config)).keyId)
        assertEquals("researcher", decoder(config).decode(issued).subject)
        val oldToken = issue(config.copy(secret = previous))
        assertEquals("researcher", decoder(config).decode(oldToken).subject)
        assertThrows(JwtException::class.java) { decoder(config(0)).decode(oldToken) }
    }

    @Test fun base64ConfigurationUsesTheSameKeyBytesForSigningAndVerification() {
        val client = ChronicleJwtClientConfiguration(
            issuer = "https://rotation.test", audience = "dashboard", base64EncodedSecret = true,
            secret = Base64.getUrlEncoder().encodeToString(current.toByteArray()),
        )
        val auth = ChronicleAuthConfiguration(configurations = setOf(client), users = setOf(
            ChronicleAuthUser(id = "researcher", email = "researcher@rotation.test"),
        ))
        val issued = requireNotNull(ConfiguredUserListingService(auth, keys, 10).issueDashboardToken("researcher"))
        assertEquals("researcher", decoder(client).decode(issued).subject)
    }

    @Test fun previousKeyExpiresWithoutRestartingTheDecoder() {
        val expiry = Instant.now().epochSecond + 2
        val running = decoder(config(expiry))
        val old = token(previous)
        assertEquals("researcher", running.decode(old).subject)
        while (Instant.now().epochSecond < expiry) Thread.sleep(25)
        assertThrows(JwtException::class.java) { running.decode(old) }
    }

    @Test fun configuredHmacAlgorithmOwnsSigningVerificationAndOverlap() {
        for (name in listOf("HS384", "HS512")) {
            val client = config().copy(signingAlgorithm = name, secret = current.repeat(2), previousSecret = previous.repeat(2))
            fun issue(secret: String): String {
                val auth = ChronicleAuthConfiguration(configurations = setOf(client.copy(secret = secret)), users = setOf(
                    ChronicleAuthUser(id = "researcher", email = "researcher@rotation.test"),
                ))
                return requireNotNull(ConfiguredUserListingService(auth, keys, 10).issueDashboardToken("researcher"))
            }
            val active = decoder(client)
            assertThrows(JwtException::class.java) { active.decode(token(client.secret)) }
            val issued = issue(client.secret)
            assertEquals(name, JWT.decode(issued).algorithm)
            assertEquals("researcher", active.decode(issued).subject)
            val old = issue(client.previousSecret)
            assertEquals("researcher", active.decode(old).subject)
            assertThrows(JwtException::class.java) { decoder(client.copy(previousSecretExpiresAt = 0)).decode(old) }
        }
    }
}
