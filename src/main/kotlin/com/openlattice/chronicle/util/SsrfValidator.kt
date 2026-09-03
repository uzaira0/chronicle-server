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
package com.openlattice.chronicle.util

import com.openlattice.chronicle.configuration.SsrfConfig
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Validator for Server-Side Request Forgery (SSRF) prevention.
 *
 * This object provides comprehensive validation of URLs and IP addresses to prevent
 * SSRF attacks. It validates:
 * - Hostnames against an allowlist
 * - Protocols (only HTTPS by default)
 * - Resolved IP addresses (blocks private IPs, localhost, link-local, metadata endpoints)
 * - DNS rebinding protection (resolves hostname before connection)
 *
 * Usage:
 * ```kotlin
 * val config = SsrfConfig.defaultConfig()
 * val validator = SsrfValidator
 *
 * // Validate a URL - throws SsrfException if invalid
 * validator.validateUrl("https://api.example.com/endpoint", config)
 *
 * // Validate and resolve hostname to IP - returns InetAddress
 * val resolvedIp = validator.validateAndResolve("api.example.com", config)
 * ```
 */
// reason: cohesive SSRF-prevention validation surface; splitting functions risks security-check behavior
@Suppress("TooManyFunctions")
public object SsrfValidator {

    private val logger = LoggerFactory.getLogger(SsrfValidator::class.java)

    private fun hostReference(host: String): String = LogSanitizer.stableFingerprint(host, prefix = "host")

    private fun ipReference(ip: String): String = LogSanitizer.stableFingerprint(ip, prefix = "ip")

    private fun urlReference(url: String): String = LogSanitizer.stableFingerprint(url, prefix = "url")

    // Cloud metadata endpoint IP addresses
    private val METADATA_IPS = setOf(
        "169.254.169.254",  // cloud metadata
        "100.100.100.200",  // Alibaba Cloud
        "192.0.0.192",      // Oracle Cloud
        "fd00:ec2::254"     // cloud IPv6 metadata
    )

    // Cloud metadata hostnames
    private val METADATA_HOSTS = setOf(
        "metadata.google.internal",
        "metadata.gcp.internal",
        "instance-data",
        "169.254.169.254"
    )

    /*
     * Conservative denylist derived from the IANA IPv4/IPv6 special-purpose
     * registries. Webhook callbacks must resolve to ordinary global-unicast
     * addresses; protocol anycast, documentation, benchmarking, translation,
     * multicast, reserved, and locally scoped ranges are not valid targets.
     *
     * Keep the broader aggregate when IANA has globally reachable exceptions
     * inside a special-purpose block. Chronicle webhooks do not need to target
     * protocol anycast services, and rejecting the aggregate avoids an SSRF
     * bypass when a new special-purpose child prefix is allocated.
     */
    private val NON_GLOBAL_IPV4_PREFIXES = listOf(
        ipPrefix("0.0.0.0", 8),
        ipPrefix("10.0.0.0", 8),
        ipPrefix("100.64.0.0", 10),
        ipPrefix("127.0.0.0", 8),
        ipPrefix("169.254.0.0", 16),
        ipPrefix("172.16.0.0", 12),
        ipPrefix("192.0.0.0", 24),
        ipPrefix("192.0.2.0", 24),
        ipPrefix("192.31.196.0", 24),
        ipPrefix("192.52.193.0", 24),
        ipPrefix("192.88.99.0", 24),
        ipPrefix("192.168.0.0", 16),
        ipPrefix("192.175.48.0", 24),
        ipPrefix("198.18.0.0", 15),
        ipPrefix("198.51.100.0", 24),
        ipPrefix("203.0.113.0", 24),
        ipPrefix("224.0.0.0", 4),
        ipPrefix("240.0.0.0", 4),
    )

    /*
     * Current IANA allocations from the IPv6 Global Unicast Address Space
     * registry. Unlisted portions of 2000::/3 are reserved, so an allowlist is
     * safer than treating the entire assignable aggregate as routable.
     */
    private val ALLOCATED_GLOBAL_UNICAST_IPV6_PREFIXES = listOf(
        ipPrefix("2001:200::", 23),
        ipPrefix("2001:400::", 23),
        ipPrefix("2001:600::", 23),
        ipPrefix("2001:800::", 22),
        ipPrefix("2001:c00::", 23),
        ipPrefix("2001:e00::", 23),
        ipPrefix("2001:1200::", 23),
        ipPrefix("2001:1400::", 22),
        ipPrefix("2001:1800::", 23),
        ipPrefix("2001:1a00::", 23),
        ipPrefix("2001:1c00::", 22),
        ipPrefix("2001:2000::", 19),
        ipPrefix("2001:4000::", 23),
        ipPrefix("2001:4200::", 23),
        ipPrefix("2001:4400::", 23),
        ipPrefix("2001:4600::", 23),
        ipPrefix("2001:4800::", 23),
        ipPrefix("2001:4a00::", 23),
        ipPrefix("2001:4c00::", 23),
        ipPrefix("2001:5000::", 20),
        ipPrefix("2001:8000::", 19),
        ipPrefix("2001:a000::", 20),
        ipPrefix("2001:b000::", 20),
        ipPrefix("2003::", 18),
        ipPrefix("2400::", 11),
        ipPrefix("2600::", 12),
        ipPrefix("2610::", 23),
        ipPrefix("2620::", 23),
        ipPrefix("2630::", 12),
        ipPrefix("2800::", 12),
        ipPrefix("2a00::", 11),
        ipPrefix("2c00::", 12),
    )
    private val SPECIAL_IPV6_PREFIXES = listOf(
        ipPrefix("2001::", 23),
        ipPrefix("2001:db8::", 32),
        ipPrefix("2002::", 16),
        ipPrefix("2620:4f:8000::", 48),
        ipPrefix("3fff::", 20),
    )

    /**
     * Validates a URL string against SSRF protection rules.
     *
     * @param urlString The URL to validate
     * @param config SSRF configuration
     * @throws SsrfException if the URL violates any SSRF protection rule
     */
    public fun validateUrl(urlString: String, config: SsrfConfig) {
        if (!config.enabled) {
            logger.debug("SSRF validation disabled, skipping check for URL")
            return
        }

        val url = urlString.toHttpUrlOrNull()
            ?: throw SsrfException.invalidUrl(urlString, "Could not parse URL")

        validateHttpUrl(url, config)
    }

    /**
     * Validates an HttpUrl against SSRF protection rules.
     *
     * @param url The HttpUrl to validate
     * @param config SSRF configuration
     * @throws SsrfException if the URL violates any SSRF protection rule
     */
    public fun validateHttpUrl(url: HttpUrl, config: SsrfConfig) {
        if (!config.enabled) {
            logger.debug("SSRF validation disabled, skipping check for URL")
            return
        }

        // 1. Validate protocol
        validateProtocol(url.scheme, config)

        // 2. Validate host is in allowlist
        validateHost(url.host, config)

        // 3. Resolve hostname and validate IP address
        validateAndResolve(url.host, config)
    }

    /**
     * Validates the protocol of a URL.
     *
     * @param scheme The URL scheme/protocol (e.g., "https", "http", "file")
     * @param config SSRF configuration
     * @throws SsrfException if the protocol is not allowed
     */
    public fun validateProtocol(scheme: String, config: SsrfConfig) {
        if (!config.enabled) return

        val normalizedScheme = scheme.lowercase()
        if (normalizedScheme !in config.allowedProtocols.map { it.lowercase() }) {
            logger.warn("SSRF: Blocked disallowed protocol: {}", LogSanitizer.sanitize(scheme))
            throw SsrfException.disallowedProtocol("", normalizedScheme)
        }
    }

    /**
     * Validates that a hostname is in the allowlist.
     *
     * @param host The hostname to validate
     * @param config SSRF configuration
     * @throws SsrfException if the host is not in the allowlist
     */
    public fun validateHost(host: String, config: SsrfConfig) {
        if (!config.enabled) return

        val normalizedHost = host.lowercase()

        validateHostSafety(normalizedHost, config)

        // Check allowlist - exact match or subdomain match
        val isAllowed = config.allowedHosts.any { allowedHost ->
            val normalizedAllowed = allowedHost.lowercase()
            normalizedHost == normalizedAllowed ||
            normalizedHost.endsWith(".$normalizedAllowed")
        }

        if (!isAllowed) {
            logger.warn("SSRF: Blocked disallowed hostRef={}", hostReference(host))
            throw SsrfException.disallowedHost(host)
        }
    }

    /**
     * Applies hostname safety checks that are independent of an allowlist.
     * Dynamic webhook destinations intentionally have no static hostname
     * allowlist, but must still reject well-known metadata authorities.
     */
    public fun validateHostSafety(host: String, config: SsrfConfig) {
        if (!config.enabled) return
        val normalizedHost = host.lowercase()
        if (config.blockMetadataEndpoints && normalizedHost in METADATA_HOSTS) {
            logger.warn("SSRF: Blocked cloud metadata hostRef={}", hostReference(host))
            throw SsrfException.metadataEndpointBlocked(host)
        }
    }

    /**
     * Resolves a hostname to IP address and validates the resolved IP.
     *
     * This provides DNS rebinding protection by validating the IP address
     * the hostname actually resolves to, not just the hostname itself.
     *
     * @param host The hostname to resolve and validate
     * @param config SSRF configuration
     * @return The resolved InetAddress
     * @throws SsrfException if the resolved IP violates SSRF rules
     */
    public fun validateAndResolve(host: String, config: SsrfConfig): InetAddress {
        return validateAndResolve(host, config) { hostname -> InetAddress.getAllByName(hostname) }
    }

    /**
     * Resolver-injectable variant used by DNS-pinned clients and deterministic
     * tests. Every answer is validated before any address is returned.
     */
    public fun validateAndResolve(
        host: String,
        config: SsrfConfig,
        resolver: (String) -> Array<InetAddress>,
    ): InetAddress {
        return validateAndResolveAll(host, config, resolver).first()
    }

    private fun validateAndResolveAll(
        host: String,
        config: SsrfConfig,
        resolver: (String) -> Array<InetAddress>,
    ): List<InetAddress> {
        if (!config.enabled) {
            return try {
                resolver(host).toList().ifEmpty {
                    throw UnknownHostException("Resolver returned no addresses")
                }
            } catch (e: UnknownHostException) {
                logger.debug(
                    "SSRF: Could not resolve hostRef={} errorType={}",
                    hostReference(host),
                    e.javaClass.simpleName,
                )
                throw SsrfException.dnsResolutionFailed(host)
            }
        }

        val addresses = try {
            resolver(host).toList().ifEmpty {
                throw UnknownHostException("Resolver returned no addresses")
            }
        } catch (e: UnknownHostException) {
            logger.warn(
                "SSRF: Could not resolve hostRef={} errorType={}",
                hostReference(host),
                e.javaClass.simpleName,
            )
            throw SsrfException.dnsResolutionFailed(host)
        }

        // Validate all resolved addresses
        for (address in addresses) {
            validateIpAddress(address, host, config)
        }

        return addresses
    }

    /**
     * Validates an IP address against SSRF protection rules.
     *
     * @param address The IP address to validate
     * @param originalHost The original hostname (for error messages)
     * @param config SSRF configuration
     * @throws SsrfException if the IP address violates SSRF rules
     */
    // reason: SSRF guard legitimately throws a distinct exception per blocked address category
    @Suppress("ThrowsCount")
    public fun validateIpAddress(address: InetAddress, originalHost: String, config: SsrfConfig) {
        if (!config.enabled) return

        val ip = address.hostAddress

        // Check for cloud metadata IPs
        if (config.blockMetadataEndpoints && ip in METADATA_IPS) {
            logger.warn("SSRF: Blocked cloud metadata ipRef={}", ipReference(ip))
            throw SsrfException.metadataEndpointBlocked(originalHost)
        }

        // Check for localhost
        if (config.blockLocalhost && address.isLoopbackAddress) {
            logger.warn("SSRF: Blocked localhost ipRef={}", ipReference(ip))
            throw SsrfException.localhostBlocked(originalHost)
        }

        // Check for link-local
        if (config.blockLinkLocal && address.isLinkLocalAddress) {
            logger.warn("SSRF: Blocked link-local ipRef={}", ipReference(ip))
            throw SsrfException.linkLocalBlocked(originalHost, ip)
        }

        // Check every non-global, non-unicast, and special-purpose range.
        if (config.blockPrivateIps && isPrivateIp(address)) {
            logger.warn("SSRF: Blocked non-global ipRef={}", ipReference(ip))
            throw SsrfException.privateIpAddress(originalHost, ip)
        }
    }

    /**
     * Checks whether an IP address is unsuitable for a public webhook target.
     *
     * Despite the legacy method name, this deliberately includes every IANA
     * special-purpose/non-global range, multicast, and reserved space, not just
     * RFC1918/RFC4193 private addresses.
     *
     * @param address The IP address to check
     * @return true if the address is not an ordinary global-unicast destination
     */
    public fun isPrivateIp(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        return when (address) {
            is Inet4Address -> NON_GLOBAL_IPV4_PREFIXES.any { it.contains(address.address) }
            is Inet6Address -> ALLOCATED_GLOBAL_UNICAST_IPV6_PREFIXES.none { it.contains(address.address) } ||
                SPECIAL_IPV6_PREFIXES.any { it.contains(address.address) }
            else -> true
        }
    }

    /**
     * Validates a redirect URL against SSRF rules.
     *
     * This should be called when following HTTP redirects to ensure
     * the redirect destination is safe.
     *
     * @param originalUrl The original request URL
     * @param redirectUrl The redirect destination URL
     * @param config SSRF configuration
     * @throws SsrfException if the redirect destination violates SSRF rules
     */
    public fun validateRedirect(originalUrl: String, redirectUrl: String, config: SsrfConfig) {
        if (!config.enabled || !config.validateRedirects) {
            return
        }

        logger.debug(
            "SSRF: Validating redirect sourceRef={} targetRef={}",
            urlReference(originalUrl),
            urlReference(redirectUrl),
        )

        validateUrl(redirectUrl, config)
    }

    /**
     * M-4: Creates an OkHttp Dns implementation that pins the resolved IP address.
     *
     * This prevents DNS rebinding TOCTOU attacks where:
     * 1. validateAndResolve() resolves hostname → safe public IP
     * 2. Attacker's DNS TTL expires, returns internal IP on re-resolve
     * 3. OkHttp connects to internal IP
     *
     * By pinning the IP at validation time, the actual connection uses the same IP.
     *
     * @param url The already-parsed URL whose canonical hostname will be pinned
     * @param config SSRF configuration
     * @return An OkHttp Dns implementation that always returns the validated IP
     */
    public fun createPinnedDns(url: HttpUrl, config: SsrfConfig): Dns {
        return createPinnedDns(url, config) { hostname -> InetAddress.getAllByName(hostname) }
    }

    /**
     * Creates a DNS implementation from one validated resolution. Any lookup
     * for a different hostname is rejected because webhook redirects are
     * disabled and no second authority is allowed to bypass the original pin.
     */
    public fun createPinnedDns(
        url: HttpUrl,
        config: SsrfConfig,
        resolver: (String) -> Array<InetAddress>,
    ): Dns {
        val canonicalHost = url.host
        val resolvedAddresses = validateAndResolveAll(canonicalHost, config, resolver)
        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (hostname != canonicalHost) {
                    throw UnknownHostException("Unexpected hostname for pinned webhook DNS")
                }
                return resolvedAddresses
            }
        }
    }

    private data class IpPrefix(
        val network: ByteArray,
        val prefixLength: Int,
    ) {
        fun contains(candidate: ByteArray): Boolean {
            if (candidate.size != network.size) return false
            val wholeBytes = prefixLength / 8
            val remainingBits = prefixLength % 8
            for (index in 0 until wholeBytes) {
                if (candidate[index] != network[index]) return false
            }
            if (remainingBits == 0) return true
            val mask = (0xFF shl (8 - remainingBits)) and 0xFF
            return (candidate[wholeBytes].toInt() and mask) ==
                (network[wholeBytes].toInt() and mask)
        }
    }

    private fun ipPrefix(address: String, prefixLength: Int): IpPrefix {
        val bytes = InetAddress.getByName(address).address
        require(prefixLength in 0..(bytes.size * 8)) { "Invalid IP prefix length" }
        return IpPrefix(bytes, prefixLength)
    }
}
