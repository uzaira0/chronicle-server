package com.openlattice.chronicle.configuration

import com.geekbeast.rhizome.configuration.RhizomeConfiguration
import com.geekbeast.rhizome.configuration.service.ConfigurationService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.PriorityOrdered

/**
 * Overlays Vault secrets onto [RhizomeConfiguration] after it is loaded from YAML
 * but before any beans that depend on it (e.g., DataSourceManager, HikariDataSource) are created.
 *
 * This enables the app to boot with env-var-based secrets in YAML templates and have Vault
 * override them at runtime when VAULT_ENABLED=true. If Vault is unavailable, the YAML values
 * are used as-is (graceful fallback).
 *
 * Secrets overlaid here (early in Spring lifecycle):
 *   - Database user/password in all HikariCP Properties (postgres + datasources)
 *   - Hazelcast server/client passwords
 *
 * JWT, SMTP, Twilio, and other secrets are overlaid in [ChronicleConfigurationPod] because
 * those configuration beans are loaded later in the Spring lifecycle.
 *
 * HIPAA §164.312(a)(2)(iv) — Encryption and access controls for electronic PHI.
 */
/**
 * Registered via @Import in ChronicleConfigurationPod.
 */
public class VaultSecretOverlayProcessor : BeanPostProcessor, PriorityOrdered {

    internal companion object {
        private val logger = LoggerFactory.getLogger(VaultSecretOverlayProcessor::class.java)
    }

    // Loaded once, lazily, from vault.yaml on the classpath
    // reason: boundary catch — any failure loading vault.yaml gracefully disables the overlay
    @Suppress("TooGenericExceptionCaught")
    private val vaultProvider: VaultSecretProvider? by lazy {
        try {
            val config = ConfigurationService.StaticLoader.loadConfiguration(VaultConfiguration::class.java)
            if (config != null && config.enabled) {
                VaultSecretProvider(config).takeIf { it.isAvailable() }
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn("vault.yaml not found or invalid; Vault overlay disabled: {}", e.message)
            null
        }
    }

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 10

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        if (bean is RhizomeConfiguration) {
            val vault = vaultProvider ?: return bean
            overlayDatabaseSecrets(bean, vault)
            overlayHazelcastSecrets(bean, vault)
        }
        return bean
    }

    private fun overlayDatabaseSecrets(config: RhizomeConfiguration, vault: VaultSecretProvider) {
        val dbUser = vault.getDatabaseUser()
        val dbPassword = vault.getDatabasePassword()

        if (dbUser == null && dbPassword == null) {
            logger.info("No database secrets found in Vault; using YAML/env var values")
            return
        }

        var overlaid = 0

        // Override in the main postgres config
        config.postgresConfiguration.ifPresent { pgConfig ->
            val props = pgConfig.hikariConfiguration
            if (dbUser != null) props.setProperty("username", dbUser)
            if (dbPassword != null) props.setProperty("password", dbPassword)
            overlaid++
        }

        // Override in all named datasources
        config.datasourceConfigurations.values.forEach { pgConfig ->
            val props = pgConfig.hikariConfiguration
            if (dbUser != null) props.setProperty("username", dbUser)
            if (dbPassword != null) props.setProperty("password", dbPassword)
            overlaid++
        }

        logger.info("Database credentials overlaid from Vault onto {} datasource(s)", overlaid)
    }

    // reason: boundary catch — reflective password overlay must degrade gracefully on any
    // reflection failure (missing field, access denied) without aborting bean post-processing
    @Suppress("TooGenericExceptionCaught")
    private fun overlayHazelcastSecrets(config: RhizomeConfiguration, vault: VaultSecretProvider) {
        val serverPassword = vault.getHazelcastServerPassword()
        val clientPassword = vault.getHazelcastClientPassword()

        config.hazelcastConfiguration.ifPresent { hzConfig ->
            if (serverPassword != null) {
                try {
                    val field = hzConfig.javaClass.getDeclaredField("password")
                    field.isAccessible = true
                    field.set(hzConfig, serverPassword)
                    logger.info("Hazelcast server password overlaid from Vault")
                } catch (e: Exception) {
                    logger.warn("Could not overlay Hazelcast server password: {}", e.message)
                }
            }
        }

        config.hazelcastClients.ifPresent { clientsMap ->
            clientsMap.values.forEach { hzClient ->
                if (clientPassword != null) {
                    try {
                        val field = hzClient.javaClass.getDeclaredField("password")
                        field.isAccessible = true
                        field.set(hzClient, clientPassword)
                    } catch (e: Exception) {
                        logger.warn("Could not overlay Hazelcast client password: {}", e.message)
                    }
                }
            }
            if (clientPassword != null) {
                logger.info("Hazelcast client password(s) overlaid from Vault")
            }
        }
    }
}
