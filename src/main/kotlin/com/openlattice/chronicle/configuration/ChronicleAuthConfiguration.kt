package com.openlattice.chronicle.configuration

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.openlattice.chronicle.users.ChronicleUserProfile
import com.geekbeast.rhizome.configuration.Configuration
import com.geekbeast.rhizome.configuration.ConfigurationKey
import com.geekbeast.rhizome.configuration.SimpleConfigurationKey
import com.geekbeast.rhizome.configuration.configuration.annotation.ReloadableConfiguration
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings

private const val CONFIG_FILE_NAME = "chronicle-auth.yaml"

@ReloadableConfiguration(uri = CONFIG_FILE_NAME)
@JsonIgnoreProperties(ignoreUnknown = true)
public data class ChronicleAuthConfiguration(
    val configurations: Set<ChronicleJwtClientConfiguration> = emptySet(),
    val defaultTestingUserId: String? = null,
    val oidc: ChronicleOidcConfiguration = ChronicleOidcConfiguration(),
    val testingLoginEnabled: Boolean = false,
    val allowProductionTestingLogin: Boolean = false,
    /** Namespaced JWT claim containing Chronicle application metadata such as roles. */
    val roleClaimNamespace: String = ChronicleRoleClaims.DEFAULT_ROLE_CLAIM_NAMESPACE,
    /**
     * BCrypt hash of the researcher dashboard password, verified by
     * `POST /chronicle/v3/auth/dashboard-login`. Never the cleartext password.
     *
     * Left null/blank the dashboard login fails closed: every attempt is rejected rather
     * than admitted, so an unconfigured deployment cannot be logged into at all.
     */
    @param:JsonAlias("dashboard_password_hash")
    val dashboardPasswordHash: String? = null,
    val users: Set<ChronicleAuthUser> = emptySet(),
) : Configuration {

    init {
        require(roleClaimNamespace.isNotBlank()) { "roleClaimNamespace must not be blank" }
    }

    internal companion object {
        @JvmStatic
        public val key = SimpleConfigurationKey(CONFIG_FILE_NAME)
    }

    @SuppressFBWarnings(
        value = ["IL_INFINITE_RECURSIVE_LOOP"],
        justification = "Kotlin companion-object key accessed from getKey(); findbugs misreads " +
            "companion-member access as self-recursion. Not recursive.",
    )
    override fun getKey(): ConfigurationKey {
        return key
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
public data class ChronicleOidcConfiguration(
    val enabled: Boolean = false,
    val providerLabel: String = "Institutional SSO",
    val publicBaseUrl: String = "",
    val issuer: String = "",
    val authorizationUri: String = "",
    val tokenUri: String = "",
    val jwkSetUri: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val identityProviderHint: String = "",
    val scopes: List<String> = listOf("openid", "email", "profile"),
    val redirectPath: String = "/chronicle/v3/auth/oidc/callback",
    val postLoginRedirectUri: String = "/chronicle",
    val cookieTokenClaim: String = "id_token",
)

@JsonIgnoreProperties(ignoreUnknown = true)
public data class ChronicleAuthUser(
    @param:JsonAlias("user_id")
    @param:JsonProperty("id")
    val id: String? = null,
    val email: String? = null,
    @param:JsonAlias("email_verified")
    val emailVerified: Boolean? = null,
    val nickname: String? = null,
    @param:JsonAlias("given_name")
    val givenName: String? = null,
    @param:JsonAlias("family_name")
    val familyName: String? = null,
    val name: String? = null,
    val username: String? = null,
    val identities: Set<ChronicleAuthIdentity> = emptySet(),
    @param:JsonAlias("app_metadata")
    val appMetadata: Map<String, Any> = emptyMap(),
) {

    private val resolvedId: String = requireNotNull(id) { "ChronicleAuthUser.id/user_id is required" }

    public fun toChronicleUserProfile(): ChronicleUserProfile = ChronicleUserProfile(
        id = resolvedId,
        email = email,
        name = name,
        nickname = nickname,
        givenName = givenName,
        familyName = familyName,
        username = username,
        connections = identities.mapNotNull { it.connection }.toSet(),
        identityUserIds = identities.mapNotNull { it.userId }.toSet(),
    )
}

@JsonIgnoreProperties(ignoreUnknown = true)
public data class ChronicleAuthIdentity(
    val connection: String? = null,
    @param:JsonAlias("user_id") val userId: String? = null,
)
