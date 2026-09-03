package com.openlattice.chronicle.configuration

/** Central role-claim extraction shared by resource-server and cookie-session authentication. */
public object ChronicleRoleClaims {
    public const val DEFAULT_ROLE_CLAIM_NAMESPACE: String = "https://chronicle.app/metadata"

    public fun extract(
        claims: Map<String, Any>,
        configuredNamespace: String,
        configuredClientId: String?,
    ): List<String> {
        val metadataRoles = ((claims[configuredNamespace] as? Map<*, *>)?.get("roles") as? Collection<*>)
            .orEmpty()
            .filterIsInstance<String>()
        val directRoles = claims["roles"] as? Collection<*>
        val realmRoles = (claims["realm_access"] as? Map<*, *>)?.get("roles") as? Collection<*>
        val resourceAccess = claims["resource_access"] as? Map<*, *>
        val clientRoles = configuredClientId
            ?.let { resourceAccess?.get(it) as? Map<*, *> }
            ?.get("roles")
            .let { it as? Collection<*> }
            .orEmpty()
            .filterIsInstance<String>()

        return (metadataRoles +
            directRoles.orEmpty().filterIsInstance<String>() +
            realmRoles.orEmpty().filterIsInstance<String>() +
            clientRoles)
            .distinct()
    }
}

/** Resolves the one configured Chronicle client that actually issued the validated token. */
internal fun ChronicleAuthConfiguration.roleClientIdForClaims(claims: Map<String, Any>): String? {
    val issuer = claims["iss"]?.toString()
    val audiences = when (val audience = claims["aud"]) {
        is String -> setOf(audience)
        is Collection<*> -> audience.filterIsInstance<String>().toSet()
        else -> emptySet()
    }

    if (oidc.enabled && oidc.issuer == issuer && oidc.clientId in audiences) {
        return oidc.clientId
    }
    return configurations.firstOrNull { configuration ->
        configuration.issuer == issuer && configuration.audience in audiences
    }?.audience
}
