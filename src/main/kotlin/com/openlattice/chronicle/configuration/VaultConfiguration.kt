package com.openlattice.chronicle.configuration

import com.fasterxml.jackson.annotation.JsonProperty
import com.geekbeast.rhizome.configuration.Configuration
import com.geekbeast.rhizome.configuration.ConfigurationKey
import com.geekbeast.rhizome.configuration.SimpleConfigurationKey
import com.geekbeast.rhizome.configuration.configuration.annotation.ReloadableConfiguration
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings

@ReloadableConfiguration(uri = "vault.yaml")
public data class VaultConfiguration(
    @param:JsonProperty("address") val address: String = "https://localhost:8200",
    @param:JsonProperty("token") val token: String = "",
    @param:JsonProperty("secretBasePath") val secretBasePath: String = "chronicle",
    @param:JsonProperty("enabled") val enabled: Boolean = false,
    @param:JsonProperty("connectionTimeoutMs") val connectionTimeoutMs: Int = 5000,
    @param:JsonProperty("readTimeoutMs") val readTimeoutMs: Int = 5000,
    // AppRole authentication (preferred over static token in production)
    @param:JsonProperty("authMethod") val authMethod: String = "token", // "token" or "approle"
    @param:JsonProperty("appRoleId") val appRoleId: String = "",
    @param:JsonProperty("appRoleSecretId") val appRoleSecretId: String = "",
) : Configuration {

    internal companion object {
        @JvmField
        public val key = SimpleConfigurationKey("vault.yaml")
    }

    @SuppressFBWarnings(
        value = ["IL_INFINITE_RECURSIVE_LOOP"],
        justification = "Kotlin companion-object key accessed from getKey(); findbugs misreads " +
            "companion-member access as self-recursion. Not recursive.",
    )
    override fun getKey(): ConfigurationKey = key
}
