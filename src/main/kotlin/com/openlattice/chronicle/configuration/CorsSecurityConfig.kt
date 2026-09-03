/*
 * Copyright (C) 2024. Chronicle.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.openlattice.chronicle.configuration

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.beans.factory.annotation.Autowired

/**
 * Spring Security CORS configuration.
 *
 * This configuration integrates with Spring Security to provide CORS handling
 * that is applied before the security filter chain. This ensures that:
 * 1. Preflight OPTIONS requests are handled correctly without authentication
 * 2. CORS headers are added to responses consistently
 * 3. The configuration is centralized and matches our security requirements
 *
 * SECURITY FEATURES:
 * - Strict origin allowlist (no wildcards with credentials)
 * - Limited HTTP methods (TRACE/TRACK blocked)
 * - Whitelisted request headers only
 * - Controlled response header exposure
 * - Preflight caching to reduce request overhead
 *
 * INTEGRATION:
 * This configuration provides a CorsConfigurationSource bean that should be
 * used in Spring Security configuration via:
 * ```kotlin
 * http.cors().configurationSource(corsConfigurationSource)
 * ```
 *
 * @author uzaira0
 */
@Configuration
public open class CorsSecurityConfig {

    public companion object {
        private val logger = LoggerFactory.getLogger(CorsSecurityConfig::class.java)
    }

    @Autowired(required = false)
    private var corsConfiguration: com.openlattice.chronicle.configuration.CorsConfiguration? = null

    /**
     * Creates the CORS configuration source bean for Spring Security.
     *
     * This bean is automatically picked up by Spring Security when CORS is enabled
     * in the security configuration. It provides the CORS rules to be applied
     * to incoming requests.
     *
     * @return CorsConfigurationSource that provides CORS configuration for all endpoints
     */
    @Bean
    public fun corsConfigurationSource(): CorsConfigurationSource {
        val config = corsConfiguration ?: run {
            logger.warn("No CORS configuration found, using restrictive defaults. " +
                    "Create cors.yaml to configure CORS properly.")
            com.openlattice.chronicle.configuration.CorsConfiguration()
        }

        // Validate configuration and log any issues
        val validationErrors = config.validate()
        if (validationErrors.isNotEmpty()) {
            validationErrors.forEach { error ->
                logger.warn(error)
            }
        }

        if (!config.enabled) {
            logger.info("CORS is DISABLED - cross-origin requests will be blocked")
            return createDisabledCorsSource()
        }

        val springCorsConfig = createSpringCorsConfig(config)

        // Log effective configuration
        logger.info("CORS configuration initialized:")
        logger.info("  Allowed origins: ${config.getEffectiveAllowedOrigins()}")
        logger.info("  Allowed methods: ${config.getEffectiveAllowedMethods()}")
        logger.info("  Allow credentials: ${config.allowCredentials}")
        logger.info("  Max age: ${config.getEffectiveMaxAge()} seconds")
        logger.info("  Development mode: ${config.developmentMode}")

        val source = UrlBasedCorsConfigurationSource()
        // Apply CORS config to all API endpoints
        source.registerCorsConfiguration("/chronicle/**", springCorsConfig)
        source.registerCorsConfiguration("/api/**", springCorsConfig)

        return source
    }

    /**
     * Creates a Spring CorsConfiguration from our CorsConfiguration.
     */
    private fun createSpringCorsConfig(config: com.openlattice.chronicle.configuration.CorsConfiguration): CorsConfiguration {
        return CorsConfiguration().apply {
            // Set allowed origins - each must be explicit (no wildcards with credentials)
            allowedOrigins = config.getEffectiveAllowedOrigins().toList()

            // Set allowed methods - TRACE/TRACK filtered out
            allowedMethods = config.getEffectiveAllowedMethods().toList()

            // Set allowed headers
            allowedHeaders = config.allowedHeaders.toList()

            // Set exposed headers
            exposedHeaders = config.exposedHeaders.toList()

            // Set credentials allowance
            allowCredentials = config.allowCredentials

            // Set preflight cache max age (in seconds)
            maxAge = config.getEffectiveMaxAge()
        }
    }

    /**
     * Creates a CORS source that effectively disables CORS.
     * This returns no configuration, which means CORS headers won't be added
     * and cross-origin requests will fail.
     */
    private fun createDisabledCorsSource(): CorsConfigurationSource {
        return CorsConfigurationSource { null }
    }

    /**
     * Creates a CorsConfiguration bean that can be injected elsewhere.
     * This is useful for components that need to access CORS settings.
     */
    @Bean
    public fun chronicleCorsConfiguration(): com.openlattice.chronicle.configuration.CorsConfiguration {
        return corsConfiguration ?: com.openlattice.chronicle.configuration.CorsConfiguration()
    }
}
