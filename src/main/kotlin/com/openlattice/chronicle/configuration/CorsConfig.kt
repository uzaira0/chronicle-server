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
// reason: file name reflects the CORS config feature; renaming the file would churn git/build for no behavior gain
@file:Suppress("MatchingDeclarationName")

package com.openlattice.chronicle.configuration

import com.fasterxml.jackson.annotation.JsonProperty
import com.geekbeast.rhizome.configuration.Configuration
import com.geekbeast.rhizome.configuration.ConfigurationKey
import com.geekbeast.rhizome.configuration.SimpleConfigurationKey
import com.geekbeast.rhizome.configuration.configuration.annotation.ReloadableConfiguration

/** Origin admission only; the same-origin dashboard does not use a configurable CORS response policy. */
@ReloadableConfiguration(uri = "cors.yaml")
public data class CorsConfiguration(
    /**
     * Whether CORS handling is enabled.
     * When false, no CORS headers are added and cross-origin requests may be blocked.
     */
    @param:JsonProperty("enabled")
    val enabled: Boolean = true,

    /**
     * List of allowed origins for cross-origin requests.
     *
     * SECURITY: Do NOT use wildcards with credentials enabled.
     * Each origin must be an exact match (protocol + host + port).
     *
     * Examples:
     * - "https://study.example.org"
     * - "http://localhost:3000" (development only)
     */
    @param:JsonProperty("allowed-origins")
    val allowedOrigins: List<String> = listOf(),

    /**
     * Development mode allows additional origins for local development.
     *
     * SECURITY: Must be false in production.
     * When true, adds localhost origins automatically.
     */
    @param:JsonProperty("development-mode")
    val developmentMode: Boolean = false,

    /**
     * Additional localhost ports to allow in development mode.
     * Only used when developmentMode is true.
     */
    @param:JsonProperty("development-ports")
    val developmentPorts: List<Int> = listOf(3000, 3001, 5173, 8080)
) : Configuration {

    internal companion object {
        @JvmField
        public val key = SimpleConfigurationKey("cors.yaml")
    }

    override fun getKey(): ConfigurationKey = Companion.key

    /**
     * Returns the effective list of allowed origins.
     * In development mode, adds localhost origins.
     */
    public fun getEffectiveAllowedOrigins(): Set<String> {
        val origins = allowedOrigins.toMutableSet()

        if (developmentMode) {
            developmentPorts.forEach { port ->
                origins.add("http://localhost:$port")
                origins.add("http://127.0.0.1:$port")
            }
        }

        return origins
    }

    /**
     * Checks if a given origin is allowed.
     * @param origin The Origin header value to check.
     * @return true if the origin is allowed, false otherwise.
     */
    public fun isOriginAllowed(origin: String?): Boolean {
        if (origin.isNullOrBlank()) return false
        if (origin == "null") return false  // Block "null" origin (sandboxed iframes, etc.)

        return origin in getEffectiveAllowedOrigins()
    }
}
