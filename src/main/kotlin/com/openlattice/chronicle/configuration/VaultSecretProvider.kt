package com.openlattice.chronicle.configuration

import org.slf4j.LoggerFactory
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.vault.authentication.AppRoleAuthentication
import org.springframework.vault.authentication.AppRoleAuthenticationOptions
import org.springframework.vault.authentication.ClientAuthentication
import org.springframework.vault.authentication.TokenAuthentication
import org.springframework.vault.client.VaultEndpoint
import org.springframework.vault.core.VaultTemplate
import org.springframework.vault.support.Versioned
import org.springframework.web.client.RestTemplate
import java.net.URI

/**
 * Reads secrets from HashiCorp Vault KV v2 engine with env var fallback.
 *
 * Secret paths under {basePath}/:
 *   database  -> password, user
 *   jwt       -> secret
 *   smtp      -> host, port, user, password
 *   hazelcast -> server-password, client-password
 *   mobile    -> signing-secret, app-key
 *   twilio    -> sid, token, from-phone
 *   crowdsec  -> bouncer-api-key
 *   grafana   -> admin-password
 *
 * If Vault is disabled, all getters return null and callers fall back to values
 * loaded from YAML/env vars. If Vault is explicitly enabled, startup fails
 * closed when Vault is unavailable or misconfigured.
 */
public open class VaultSecretProvider(private val config: VaultConfiguration) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(VaultSecretProvider::class.java)
        private const val DATABASE_PATH = "database"
        private const val JWT_PATH = "jwt"
        private const val SMTP_PATH = "smtp"
        private const val HAZELCAST_PATH = "hazelcast"
        private const val MOBILE_PATH = "mobile"
        private const val TWILIO_PATH = "twilio"
        private const val CROWDSEC_PATH = "crowdsec"
        private const val GRAFANA_PATH = "grafana"
    }

    private var vaultTemplate: VaultTemplate? = null
    private var available: Boolean = false

    init {
        if (config.enabled) {
            require(hasCredentials()) {
                "Vault is enabled but ${config.authMethod} credentials are not configured"
            }
            initializeVault()
        } else {
            logger.info("Vault integration disabled. Using environment variables for secrets.")
        }
    }

    // reason: boundary catch — any Vault connect/seal/config failure must fail closed via IllegalStateException
    @Suppress("TooGenericExceptionCaught")
    private fun initializeVault() {
        try {
            val endpoint = VaultEndpoint.from(URI.create(config.address))
            val authentication = buildAuthentication(endpoint)
            vaultTemplate = VaultTemplate(endpoint, authentication)

            // Fail startup if Vault is explicitly enabled but unreachable or sealed.
            vaultTemplate!!.opsForSys().health()
            vaultTemplate!!.opsForVersionedKeyValue(config.secretBasePath)
            available = true
            logger.info(
                "Vault secret provider initialized successfully at {} (auth={})",
                config.address, config.authMethod
            )
        } catch (e: Exception) {
            vaultTemplate = null
            available = false
            throw IllegalStateException(
                "Vault is enabled but unavailable at ${config.address}; refusing to fall back to static secrets",
                e
            )
        }
    }

    private fun hasCredentials(): Boolean {
        return when (config.authMethod) {
            "approle" -> config.appRoleId.isNotBlank() && config.appRoleSecretId.isNotBlank()
            else -> config.token.isNotBlank()
        }
    }

    private fun buildAuthentication(endpoint: VaultEndpoint): ClientAuthentication {
        return when (config.authMethod) {
            "approle" -> {
                logger.info("Using AppRole authentication for Vault")
                val options = AppRoleAuthenticationOptions.builder()
                    .roleId(AppRoleAuthenticationOptions.RoleId.provided(config.appRoleId))
                    .secretId(AppRoleAuthenticationOptions.SecretId.provided(config.appRoleSecretId))
                    .build()
                val restTemplate = RestTemplate(SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(config.connectionTimeoutMs)
                    setReadTimeout(config.readTimeoutMs)
                })
                restTemplate.uriTemplateHandler =
                    org.springframework.web.util.DefaultUriBuilderFactory(
                        "${endpoint.scheme}://${endpoint.host}:${endpoint.port}"
                    )
                AppRoleAuthentication(options, restTemplate)
            }
            else -> {
                logger.info("Using Token authentication for Vault")
                TokenAuthentication(config.token)
            }
        }
    }

    public fun isAvailable(): Boolean = available

    // ── Database secrets ─────────────────────────────────────────────────────
    public fun getDatabasePassword(): String? = getSecret(DATABASE_PATH, "password")
    public fun getDatabaseUser(): String? = getSecret(DATABASE_PATH, "user")

    // ── JWT secrets ──────────────────────────────────────────────────────────
    public fun getJwtSecret(): String? = getSecret(JWT_PATH, "secret")

    // ── SMTP secrets ─────────────────────────────────────────────────────────
    public fun getSmtpHost(): String? = getSecret(SMTP_PATH, "host")
    public fun getSmtpPort(): String? = getSecret(SMTP_PATH, "port")
    public fun getSmtpUser(): String? = getSecret(SMTP_PATH, "user")
    public fun getSmtpPassword(): String? = getSecret(SMTP_PATH, "password")

    // ── Hazelcast secrets ────────────────────────────────────────────────────
    public fun getHazelcastServerPassword(): String? = getSecret(HAZELCAST_PATH, "server-password")
    public fun getHazelcastClientPassword(): String? = getSecret(HAZELCAST_PATH, "client-password")

    // ── Mobile secrets ───────────────────────────────────────────────────────
    public fun getMobileSigningSecret(): String? = getSecret(MOBILE_PATH, "signing-secret")
    public fun getMobileAppKey(): String? = getSecret(MOBILE_PATH, "app-key")

    // ── Twilio secrets ───────────────────────────────────────────────────────
    public fun getTwilioSid(): String? = getSecret(TWILIO_PATH, "sid")
    public fun getTwilioToken(): String? = getSecret(TWILIO_PATH, "token")
    public fun getTwilioFromPhone(): String? = getSecret(TWILIO_PATH, "from-phone")

    // ── CrowdSec secrets ─────────────────────────────────────────────────────
    public fun getCrowdsecBouncerApiKey(): String? = getSecret(CROWDSEC_PATH, "bouncer-api-key")

    // ── Grafana secrets ──────────────────────────────────────────────────────
    public fun getGrafanaAdminPassword(): String? = getSecret(GRAFANA_PATH, "admin-password")

    /**
     * Read a single field from a Vault KV v2 secret.
     * Returns null if Vault is unavailable or the field doesn't exist.
     */
    // reason: boundary catch — a missing field or transient Vault error must degrade to a null secret
    @Suppress("TooGenericExceptionCaught")
    public fun getSecret(path: String, field: String): String? {
        if (!available || vaultTemplate == null) return null

        return try {
            val response: Versioned<Map<String, Any>>? = vaultTemplate!!
                .opsForVersionedKeyValue(config.secretBasePath)
                .get(path)

            response?.data?.get(field)?.toString()
        } catch (e: Exception) {
            logger.warn("Failed to read secret {}/{}: {}", path, field, e.message)
            null
        }
    }

    /**
     * Write a KV v2 secret. Replaces the whole secret at [path] with [values] (KV v2 put
     * semantics). Returns false (and writes nothing) if Vault is unavailable. Used by
     * study-encryption key provisioning to persist per-study private keys.
     */
    // reason: boundary catch — any Vault write error must degrade to a false result without propagating
    @Suppress("TooGenericExceptionCaught")
    public fun putSecret(path: String, values: Map<String, String>): Boolean {
        if (!available || vaultTemplate == null) return false

        return try {
            vaultTemplate!!
                .opsForVersionedKeyValue(config.secretBasePath)
                .put(path, values)
            true
        } catch (e: Exception) {
            logger.warn("Failed to write secret {}: {}", path, e.message)
            false
        }
    }
}
