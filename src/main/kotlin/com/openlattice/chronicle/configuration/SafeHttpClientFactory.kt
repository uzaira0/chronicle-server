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

import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.util.SsrfException
import com.openlattice.chronicle.util.SsrfValidator
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Factory for creating OkHttpClient instances with SSRF protection.
 *
 * This factory creates HTTP clients that validate all outbound requests against
 * SSRF protection rules before allowing the connection. It provides:
 * - URL validation (host allowlist, protocol restrictions)
 * - IP validation (blocks private IPs, localhost, link-local, metadata endpoints)
 * - DNS rebinding protection (validates resolved IPs)
 * - Redirect validation (validates redirect destinations)
 * - Redirect limiting (maximum 3 redirects by default)
 *
 * Usage:
 * ```kotlin
 * // Using default configuration
 * val client = SafeHttpClientFactory.createClient()
 *
 * // Using custom configuration
 * val config = SsrfConfig(allowedHosts = setOf("api.example.com"))
 * val client = SafeHttpClientFactory.createClient(config)
 *
 * // Adding additional interceptors
 * val client = SafeHttpClientFactory.createClientBuilder(config)
 *     .addInterceptor(authInterceptor)
 *     .build()
 * ```
 */
public object SafeHttpClientFactory {

    private val logger = LoggerFactory.getLogger(SafeHttpClientFactory::class.java)

    // Default timeouts
    private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
    private const val DEFAULT_READ_TIMEOUT_MS = 30_000L
    private const val DEFAULT_WRITE_TIMEOUT_MS = 30_000L

    internal fun safeTargetReference(target: String): String =
        LogSanitizer.stableFingerprint(target, prefix = "url")

    private fun safeHostReference(host: String): String =
        LogSanitizer.stableFingerprint(host, prefix = "host")

    /**
     * Creates an OkHttpClient with SSRF protection using default configuration.
     *
     * @return A new OkHttpClient instance with SSRF protection enabled
     */
    @JvmStatic
    public fun createClient(): OkHttpClient {
        return createClient(SsrfConfig.defaultConfig())
    }

    /**
     * Creates an OkHttpClient with SSRF protection.
     *
     * @param config SSRF configuration
     * @return A new OkHttpClient instance with SSRF protection
     */
    @JvmStatic
    public fun createClient(config: SsrfConfig): OkHttpClient {
        return createClientBuilder(config).build()
    }

    /**
     * Creates an OkHttpClient.Builder with SSRF protection.
     *
     * Use this method when you need to add additional configuration
     * to the client (e.g., authentication interceptors).
     *
     * @param config SSRF configuration
     * @return A new OkHttpClient.Builder with SSRF protection configured
     */
    @JvmStatic
    public fun createClientBuilder(config: SsrfConfig): OkHttpClient.Builder {
        return OkHttpClient.Builder()
            .connectTimeout(DEFAULT_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DEFAULT_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(DEFAULT_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // Add SSRF validation interceptor (runs before connection)
            .addInterceptor(SsrfInterceptor(config))
            // Custom DNS resolver that validates resolved IPs
            .dns(SsrfDns(config))
            // Limit redirects
            .followRedirects(config.validateRedirects)
            .followSslRedirects(config.validateRedirects)
            // Add redirect validator interceptor if redirects are enabled
            .apply {
                if (config.validateRedirects) {
                    addNetworkInterceptor(RedirectValidatorInterceptor(config))
                }
            }
    }

    /**
     * Creates an OkHttpClient.Builder with SSRF protection and infinite timeouts.
     *
     * This matches the original RetrofitFactory behavior for backward compatibility.
     * Note: Infinite timeouts are generally not recommended for production.
     *
     * @param config SSRF configuration
     * @return A new OkHttpClient.Builder with SSRF protection and infinite timeouts
     */
    @JvmStatic
    public fun createClientBuilderWithInfiniteTimeouts(config: SsrfConfig): OkHttpClient.Builder {
        return createClientBuilder(config)
            .connectTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
    }

    /**
     * Interceptor that validates requests against SSRF protection rules.
     *
     * This interceptor runs before the connection is established and validates:
     * - The protocol is allowed
     * - The host is in the allowlist
     */
    public class SsrfInterceptor(private val config: SsrfConfig) : Interceptor {

        private val logger = LoggerFactory.getLogger(SsrfInterceptor::class.java)

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url

            if (config.enabled) {
                logger.debug("SSRF: Validating request targetRef={}", safeTargetReference(url.toString()))

                try {
                    // Validate protocol
                    SsrfValidator.validateProtocol(url.scheme, config)

                    // Validate host is in allowlist
                    SsrfValidator.validateHost(url.host, config)
                } catch (e: SsrfException) {
                    logger.warn(
                        "SSRF: Blocked request targetRef={} violation={}",
                        safeTargetReference(url.toString()),
                        e.violationType,
                    )
                    throw e
                }
            }

            return chain.proceed(request)
        }
    }

    /**
     * Custom DNS resolver that validates resolved IP addresses.
     *
     * This provides DNS rebinding protection by validating the actual IP address
     * that a hostname resolves to, not just the hostname itself.
     */
    public class SsrfDns(private val config: SsrfConfig) : Dns {

        private val logger = LoggerFactory.getLogger(SsrfDns::class.java)

        override fun lookup(hostname: String): List<InetAddress> {
            if (!config.enabled) {
                return Dns.SYSTEM.lookup(hostname)
            }

            logger.debug("SSRF: Resolving and validating DNS hostRef={}", safeHostReference(hostname))

            try {
                // Resolve hostname
                val addresses = InetAddress.getAllByName(hostname)

                // Validate each resolved address
                for (address in addresses) {
                    SsrfValidator.validateIpAddress(address, hostname, config)
                }

                return addresses.toMutableList()
            } catch (e: SsrfException) {
                logger.warn(
                    "SSRF: Blocked DNS lookup hostRef={} violation={}",
                    safeHostReference(hostname),
                    e.violationType,
                )
                throw e
            }
        }
    }

    /**
     * Network interceptor that validates redirect destinations.
     *
     * This interceptor runs after the network call and validates any redirects
     * to ensure they don't lead to blocked destinations.
     */
    public class RedirectValidatorInterceptor(private val config: SsrfConfig) : Interceptor {

        private val logger = LoggerFactory.getLogger(RedirectValidatorInterceptor::class.java)

        // Track redirect count per chain of requests
        private val redirectCounts = ThreadLocal<Int>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)

            if (!config.enabled || !config.validateRedirects) {
                return response
            }

            // Check if this is a redirect
            if (response.isRedirect) {
                val location = response.header("Location")
                if (location != null) {
                    // Track redirect count
                    val count = (redirectCounts.get() ?: 0) + 1
                    if (count > config.maxRedirects) {
                        logger.warn(
                            "SSRF: Redirect limit exceeded ({} redirects) sourceRef={}",
                            count,
                            safeTargetReference(request.url.toString()),
                        )
                        response.close()
                        throw SsrfException.tooManyRedirects(request.url.toString(), count)
                    }
                    redirectCounts.set(count)

                    // Resolve relative URLs
                    val redirectUrl = request.url.resolve(location)?.toString() ?: location

                    // Validate redirect destination
                    try {
                        SsrfValidator.validateRedirect(
                            request.url.toString(),
                            redirectUrl,
                            config
                        )
                    } catch (e: SsrfException) {
                        logger.warn(
                            "SSRF: Blocked redirect targetRef={} violation={}",
                            safeTargetReference(redirectUrl),
                            e.violationType,
                        )
                        response.close()
                        throw e
                    }
                }
            } else {
                // Reset redirect count on non-redirect response
                redirectCounts.remove()
            }

            return response
        }
    }
}
