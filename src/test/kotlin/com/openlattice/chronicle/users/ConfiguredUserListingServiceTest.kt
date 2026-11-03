package com.openlattice.chronicle.users

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.configuration.ChronicleAuthUser
import com.openlattice.chronicle.configuration.ChronicleJwtClientConfiguration
import com.openlattice.chronicle.configuration.JwtKeyMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.InvalidAlgorithmParameterException

/** The self-host dashboard password login mints its session through this service; no identity provider exists. */
class ConfiguredUserListingServiceTest {
    private val secret = "selfhost-jwt-secret-with-at-least-32-bytes"
    private val hs256 = JwtKeyMaterial("HS256", "selfhost", null, null, null)
    private val localTesting = ChronicleJwtClientConfiguration(
        audience = "dummy-client-id", issuer = "https://localhost/", secret = secret, testingTokenIssuer = true,
    )
    private val oidcBroker = ChronicleJwtClientConfiguration(
        audience = "", issuer = "", jwkSetUri = "", signingAlgorithm = "RS256", testingTokenIssuer = false,
    )
    private val admin = ChronicleAuthUser(id = "dashboard-admin", email = "pi@example.org",
        appMetadata = mapOf("roles" to listOf("admin", 7)))
    private val assistant = ChronicleAuthUser(id = "assistant", email = "ra@example.org")

    private fun service(
        configurations: Set<ChronicleJwtClientConfiguration> = setOf(localTesting, oidcBroker),
        users: Set<ChronicleAuthUser> = setOf(admin),
        testingLoginEnabled: Boolean = false,
        defaultTestingUserId: String? = null,
    ) = ConfiguredUserListingService(
        ChronicleAuthConfiguration(configurations = configurations, users = users, testingLoginEnabled = testingLoginEnabled,
            defaultTestingUserId = defaultTestingUserId, sessionIdleMinutes = 15),
        hs256,
        accessTokenExpiryMinutes = 60,
    )

    /**
     * The pilot bundle: testing login off, one dashboard user, the HS256 local issuer beside a disabled OIDC entry.
     * The password login gets a token the request decoder accepts (issuer, audience, subject, roles, one-hour
     * expiry); the unauthenticated testing bridge gets nothing.
     */
    @Test
    fun selfHostDashboardLoginMintsAVerifiableSessionWhileTestingLoginStaysClosed() {
        val listing = service()

        assertNull(listing.issueTestingToken(null))
        val token = requireNotNull(listing.issueDashboardToken(null))
        val verified = JWT.require(Algorithm.HMAC256(secret)).withIssuer("https://localhost/")
            .withAudience("dummy-client-id").build().verify(token)
        assertEquals("dashboard-admin", verified.subject)
        assertEquals("pi@example.org", verified.getClaim("email").asString())
        assertEquals(false, verified.getClaim("email_verified").asBoolean())
        assertEquals(listOf("admin"), verified.getClaim(ChronicleAuthConfiguration().roleClaimNamespace).asMap()["roles"])
        assertEquals(3600L, verified.expiresAtAsInstant.epochSecond - verified.issuedAtAsInstant.epochSecond)
        assertEquals(listOf(token), listing.jwtTokens["dashboard-admin"])
        assertEquals("dashboard-admin", listing.getUser("dashboard-admin").id)
    }

    @Test
    fun testingLoginWhenEnabledSignsInTheDefaultOrRequestedUserOnly() {
        val listing = service(users = setOf(admin, assistant), testingLoginEnabled = true, defaultTestingUserId = "assistant")
        fun subject(token: String?) = token?.let { JWT.decode(it).subject }

        assertEquals("assistant", subject(listing.issueTestingToken(null)))
        assertEquals("assistant", subject(listing.issueTestingToken(" ")))
        assertEquals("dashboard-admin", subject(listing.issueTestingToken("dashboard-admin")))
        assertNull(listing.issueTestingToken("someone-else"))
    }

    /** No local user, no local issuer, or an issuer whose keys live behind a JWKS URL: nothing can be minted here. */
    @Test
    fun noSessionIsMintedWithoutALocalUserAndALocallySignedIssuer() {
        assertNull(service(users = emptySet()).issueDashboardToken(null))
        val withoutIssuer = service(configurations = setOf(oidcBroker))
        assertNull(withoutIssuer.issueDashboardToken(null))
        assertEquals(emptyMap<String, List<String>>(), withoutIssuer.jwtTokens)
        val remoteKeys = service(configurations = setOf(localTesting.copy(jwkSetUri = "https://idp.example.org/jwks")))
        assertNull(remoteKeys.issueDashboardToken(null))
        assertEquals(emptyMap<String, List<String>>(), remoteKeys.jwtTokens)
    }

    /** The operator's HMAC strength is honoured; an unknown algorithm stops startup instead of minting anything. */
    @Test
    fun configuredHmacAlgorithmIsHonouredAndAnUnknownOneFailsAtStartup() {
        for ((name, algorithm) in listOf("HS384" to Algorithm.HMAC384(secret), "HS512" to Algorithm.HMAC512(secret))) {
            val token = service(configurations = setOf(localTesting.copy(signingAlgorithm = name))).issueDashboardToken(null)!!
            assertEquals(name, JWT.decode(token).algorithm)
            JWT.require(algorithm).build().verify(token)
        }
        val error = assertThrows(InvalidAlgorithmParameterException::class.java) {
            service(configurations = setOf(localTesting.copy(signingAlgorithm = "none")))
        }
        assertEquals("Algorithm none not recognized.", error.message)
    }

    /** UserListingService.getUser's contract: PrincipalDirectoryController.syncCallingUser turns this into a 401. */
    @Test
    fun anUnknownUserIdIsRefusedWithNoSuchElementException() {
        assertThrows(NoSuchElementException::class.java) { service().getUser("removed-user") }
    }
}
