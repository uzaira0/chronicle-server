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

import com.fasterxml.jackson.annotation.JsonProperty
import com.geekbeast.rhizome.configuration.Configuration
import com.geekbeast.rhizome.configuration.ConfigurationKey
import com.geekbeast.rhizome.configuration.SimpleConfigurationKey
import com.geekbeast.rhizome.configuration.configuration.annotation.ReloadableConfiguration
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings

/**
 * Configuration for Server-Side Request Forgery (SSRF) prevention.
 *
 * This configuration controls which hosts, protocols, and IP ranges are allowed
 * for outbound HTTP requests from Chronicle. By default, it blocks:
 * - Private IP ranges (10.x, 172.16-31.x, 192.168.x)
 * - Localhost (127.0.0.1, ::1)
 * - Link-local addresses (169.254.x.x, fe80::)
 * - Cloud metadata endpoints (169.254.169.254)
 * - Non-HTTPS protocols (file://, gopher://, ftp://, etc.)
 *
 * Only explicitly whitelisted hosts are allowed for outbound requests.
 */
@ReloadableConfiguration(uri = "ssrf.yaml")
public data class SsrfConfig(
    /**
     * List of allowed hostnames for outbound requests.
     * Only requests to these hosts will be permitted.
     * Default: none. Deployments must opt in trusted external hosts explicitly.
     */
    @param:JsonProperty(ALLOWED_HOSTS)
    val allowedHosts: Set<String> = DEFAULT_ALLOWED_HOSTS,

    /**
     * List of allowed protocols for outbound requests.
     * Default: HTTPS only
     */
    @param:JsonProperty(ALLOWED_PROTOCOLS)
    val allowedProtocols: Set<String> = DEFAULT_ALLOWED_PROTOCOLS,

    /**
     * Whether to block private IP ranges (10.x, 172.16-31.x, 192.168.x)
     * Default: true
     */
    @param:JsonProperty(BLOCK_PRIVATE_IPS)
    val blockPrivateIps: Boolean = true,

    /**
     * Whether to block localhost addresses (127.0.0.1, ::1)
     * Default: true
     */
    @param:JsonProperty(BLOCK_LOCALHOST)
    val blockLocalhost: Boolean = true,

    /**
     * Whether to block link-local addresses (169.254.x.x, fe80::)
     * Default: true
     */
    @param:JsonProperty(BLOCK_LINK_LOCAL)
    val blockLinkLocal: Boolean = true,

    /**
     * Whether to block cloud metadata endpoints (169.254.169.254)
     * Default: true
     */
    @param:JsonProperty(BLOCK_METADATA_ENDPOINTS)
    val blockMetadataEndpoints: Boolean = true,

    /**
     * Whether to validate redirect destinations against SSRF rules
     * Default: true
     */
    @param:JsonProperty(VALIDATE_REDIRECTS)
    val validateRedirects: Boolean = true,

    /**
     * Maximum number of redirects to follow
     * Default: 3
     */
    @param:JsonProperty(MAX_REDIRECTS)
    val maxRedirects: Int = DEFAULT_MAX_REDIRECTS,

    /**
     * Whether SSRF protection is enabled
     * Default: true
     */
    @param:JsonProperty(ENABLED)
    val enabled: Boolean = true
) : Configuration {

    internal companion object {
        @JvmField
        public val key = SimpleConfigurationKey("ssrf.yaml")

        // Property names
        public const val ALLOWED_HOSTS = "allowedHosts"
        public const val ALLOWED_PROTOCOLS = "allowedProtocols"
        public const val BLOCK_PRIVATE_IPS = "blockPrivateIps"
        public const val BLOCK_LOCALHOST = "blockLocalhost"
        public const val BLOCK_LINK_LOCAL = "blockLinkLocal"
        public const val BLOCK_METADATA_ENDPOINTS = "blockMetadataEndpoints"
        public const val VALIDATE_REDIRECTS = "validateRedirects"
        public const val MAX_REDIRECTS = "maxRedirects"
        public const val ENABLED = "enabled"

        // Defaults
        public val DEFAULT_ALLOWED_HOSTS: Set<String> = emptySet()

        public val DEFAULT_ALLOWED_PROTOCOLS: Set<String> = setOf("https")

        public const val DEFAULT_MAX_REDIRECTS: Int = 3

        /**
         * Creates a default configuration with all protections enabled.
         */
        @JvmStatic
        public fun defaultConfig(): SsrfConfig = SsrfConfig()

        /**
         * Creates a permissive configuration for testing (NOT for production).
         */
        @JvmStatic
        public fun testConfig(): SsrfConfig = SsrfConfig(
            enabled = false
        )
    }

    @SuppressFBWarnings(
        value = ["IL_INFINITE_RECURSIVE_LOOP"],
        justification = "Kotlin companion-object key accessed from getKey(); findbugs misreads " +
            "companion-member access as self-recursion. Not recursive.",
    )
    override fun getKey(): ConfigurationKey = key
}
