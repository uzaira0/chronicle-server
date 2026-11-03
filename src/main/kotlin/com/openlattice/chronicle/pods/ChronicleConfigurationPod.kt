package com.openlattice.chronicle.pods

import com.geekbeast.hazelcast.PreHazelcastUpgradeService
import com.geekbeast.jdbc.DataSourceManager
import com.geekbeast.rhizome.pods.ConfigurationLoader
import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.configuration.ChronicleConfiguration
import com.openlattice.chronicle.configuration.CorsConfiguration
import com.openlattice.chronicle.configuration.ErrorSanitizationConfig
import com.openlattice.chronicle.configuration.MobileSecurityConfiguration
import com.openlattice.chronicle.configuration.RateLimitConfiguration
import com.openlattice.chronicle.configuration.TwilioConfiguration
import com.openlattice.chronicle.configuration.VaultConfiguration
import com.openlattice.chronicle.configuration.VaultSecretOverlayProcessor
import com.openlattice.chronicle.configuration.VaultSecretProvider
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.upgrades.RuntimeDatabaseValidationService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 *
 * @author Matthew Tamayo-Rios <matthew@openlattice.com>
 */
@Configuration
@Import(VaultSecretOverlayProcessor::class)
public open class ChronicleConfigurationPod(
    private val configurationLoader: ConfigurationLoader,
) {

    public companion object {
        private val logger = LoggerFactory.getLogger(ChronicleConfigurationPod::class.java)
    }

    @Bean
    // reason: boundary catch — a missing/invalid vault.yaml must fall back to a disabled VaultConfiguration
    @Suppress("TooGenericExceptionCaught")
    public fun vaultConfiguration(): VaultConfiguration {
        return try {
            configurationLoader.logAndLoad("Vault Configuration", VaultConfiguration::class.java)
        } catch (e: Exception) {
            logger.info("Vault configuration not found (vault.yaml). Vault integration disabled.")
            logger.debug("Vault configuration load failed", e)
            VaultConfiguration()
        }
    }

    @Bean
    public fun vaultSecretProvider(vaultConfiguration: VaultConfiguration): VaultSecretProvider {
        return VaultSecretProvider(vaultConfiguration)
    }

    @Bean
    public fun chronicleConfiguration(): ChronicleConfiguration {
        return configurationLoader.logAndLoad("Chronicle Configuration", ChronicleConfiguration::class.java)
    }

    @Bean
    public fun chronicleAuthConfiguration(vaultSecretProvider: VaultSecretProvider): ChronicleAuthConfiguration {
        val config = configurationLoader.logAndLoad("Chronicle Auth Configuration", ChronicleAuthConfiguration::class.java)
        return overlayVaultSecrets(config, vaultSecretProvider)
    }

    /**
     * When Vault is enabled and reachable, overlays the JWT signing secret from Vault KV v2
     * (path: {secretBasePath}/jwt → field: secret) onto the YAML-loaded auth configuration.
     * Falls back to the YAML/env var values when Vault is disabled or unavailable.
     */
    private fun overlayVaultSecrets(config: ChronicleAuthConfiguration, vault: VaultSecretProvider): ChronicleAuthConfiguration {
        if (!vault.isAvailable()) return config

        val jwtSecret = vault.getJwtSecret()
        if (jwtSecret == null) {
            logger.warn("Vault is available but chronicle/jwt secret not found. Using YAML value.")
            return config
        }

        val updatedConfigs = config.configurations.map { jwtConfig ->
            jwtConfig.copy(secret = jwtSecret)
        }.toSet()

        logger.info("JWT signing secret overridden from Vault (chronicle/jwt)")
        return config.copy(configurations = updatedConfigs)
    }

    @Bean
    // reason: boundary catch — a missing/invalid cors.yaml must fall back to default CorsConfiguration
    @Suppress("TooGenericExceptionCaught")
    public fun corsConfiguration(): CorsConfiguration {
        return try {
            configurationLoader.logAndLoad("CORS Configuration", CorsConfiguration::class.java)
        } catch (e: Exception) {
            logger.warn("CORS configuration not found, using defaults. Create cors.yaml to configure CORS.")
            logger.debug("CORS configuration load failed", e)
            CorsConfiguration()
        }
    }

    @Bean
    // reason: boundary catch — a missing/invalid error-sanitization.yaml must fall back to secure defaults
    @Suppress("TooGenericExceptionCaught")
    public fun errorSanitizationConfig(): ErrorSanitizationConfig {
        return try {
            val config = configurationLoader.logAndLoad("Error Sanitization Configuration", ErrorSanitizationConfig::class.java)
            // Log any validation warnings
            config.validate().forEach { warning ->
                logger.warn(warning)
            }
            config
        } catch (e: Exception) {
            logger.warn("Error sanitization configuration not found, using secure defaults. Create error-sanitization.yaml to customize.")
            logger.debug("Error sanitization configuration load failed", e)
            ErrorSanitizationConfig()
        }
    }

    @Bean
    public fun mobileSecurityConfiguration(): MobileSecurityConfiguration {
        return configurationLoader.logAndLoad("Mobile Security Configuration", MobileSecurityConfiguration::class.java)
    }

    @Bean
    public fun rateLimitConfiguration(): RateLimitConfiguration {
        return configurationLoader.logAndLoad("Rate Limit Configuration", RateLimitConfiguration::class.java)
    }

    @Bean
    public fun twilioConfiguration(vaultSecretProvider: VaultSecretProvider): TwilioConfiguration {
        val config = configurationLoader.logAndLoad("Twilio Configuration", TwilioConfiguration::class.java)
        return overlayTwilioVaultSecrets(config, vaultSecretProvider).validated()
    }

    /**
     * When Vault is enabled, overlays Twilio credentials from Vault KV v2
     * (path: {secretBasePath}/twilio -> sid, token, from-phone).
     */
    private fun overlayTwilioVaultSecrets(config: TwilioConfiguration, vault: VaultSecretProvider): TwilioConfiguration {
        if (!vault.isAvailable()) return config

        val sid = vault.getTwilioSid()
        val token = vault.getTwilioToken()
        val fromPhone = vault.getTwilioFromPhone()

        if (sid == null && token == null && fromPhone == null) return config

        logger.info("Twilio credentials overridden from Vault (chronicle/twilio)")
        return config.copy(
            sid = sid ?: config.sid,
            token = token ?: config.token,
            defaultFromPhone = fromPhone ?: config.defaultFromPhone
        )
    }

    @Bean
    public fun storageResolver(
        chronicleConfiguration: ChronicleConfiguration,
        dataSourceManager: DataSourceManager,
    ): StorageResolver {
        return StorageResolver(dataSourceManager, chronicleConfiguration.storageConfiguration)
    }

    @Bean
    public fun runtimeDatabaseValidationService(
        storageResolver: StorageResolver,
    ): PreHazelcastUpgradeService {
        return RuntimeDatabaseValidationService(storageResolver)
    }

}
