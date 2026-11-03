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

import com.google.common.net.InetAddresses
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.filters.writeFilterError
import com.openlattice.chronicle.util.ClientIpRecord
import com.openlattice.chronicle.util.ClientIpResolver
import com.openlattice.chronicle.util.LogSanitizer
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.ConsumptionProbe
import io.github.bucket4j.TimeMeter
import io.github.bucket4j.distributed.proxy.ClientSideConfig
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.grid.hazelcast.HazelcastProxyManager
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.web.util.matcher.IpAddressMatcher
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/**
 * Distributed rate limiting of unauthenticated work, using Bucket4j with a Hazelcast backend.
 *
 * Only pre-authentication endpoints marked with [RateLimit] (login, enrollment) are limited here;
 * authenticated endpoints are never limited. [PreAuthenticationRateLimitFilter] is the IP-keyed
 * instance that counts failed unauthenticated attempts on every path. A 429 carries Retry-After.
 *
 * Rate limiting algorithm: Token Bucket
 * - Tokens are added at a fixed rate (requestsPerMinute / 60 per second)
 * - Each request consumes one token
 * - Burst capacity allows temporary spikes
 *
 * Hazelcast is used for distributed state:
 * - Rate limit state is shared across all cluster nodes
 * - Consistent limiting regardless of which node receives requests
 *
 * @author uzaira0
 */
public open class RateLimitFilter(
    hazelcastInstance: HazelcastInstance,
    private val config: RateLimitConfiguration,
    @Volatile private var handlerMapping: RequestMappingHandlerMapping?,
    private val preAuthentication: Boolean = false,
    bucketClock: TimeMeter? = null,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(RateLimitFilter::class.java)

    public fun bindHandlerMapping(mapping: RequestMappingHandlerMapping) {
        check(handlerMapping == null || handlerMapping === mapping) { "Rate limit handler mapping is already bound" }
        handlerMapping = mapping
    }

    internal companion object {
        private const val AUTHENTICATION_RESERVATION = "chronicle.authenticationRateLimitReservation"
        internal const val VALIDATED_CREDENTIAL = "chronicle.validatedCredential"

        internal fun refundAuthenticationReservation(request: HttpServletRequest) {
            (request.getAttribute(AUTHENTICATION_RESERVATION) as? Runnable)?.run()
        }

        public const val HEADER_RETRY_AFTER = "Retry-After"

        // Private IP ranges for CIDR matching
        private val PRIVATE_IP_RANGES = listOf(
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "127.0.0.0/8",
            "::1/128",
            "fc00::/7"
        )

        // Rate limit key prefix
        private const val KEY_PREFIX = "rl:"

    }

    // Hazelcast map for storing bucket state
    private val bucketMap: IMap<String, ByteArray> = hazelcastInstance.getMap(config.hazelcastMapName)

    // Bucket4j proxy manager for distributed buckets
    private val proxyManager: ProxyManager<String> = if (bucketClock == null) {
        HazelcastProxyManager<String>(bucketMap)
    } else {
        HazelcastProxyManager(bucketMap, ClientSideConfig.getDefault().withClientClock(bucketClock))
    }

    // Cache for parsed CIDR ranges
    private val cidrCache = ConcurrentHashMap<String, IpAddressMatcher>()

    // Pre-parsed trusted proxy CIDR ranges for hot-path client IP extraction.
    private val trustedProxyRanges: List<IpAddressMatcher> = ClientIpResolver.parseTrustedProxyCidrs(
        ClientIpResolver.deploymentTrustedProxyCidrs(config.trustedProxyCidrs)
    )

    // Cache for endpoint rate limit annotations
    private val annotationCache = ConcurrentHashMap<HandlerMethod, RateLimit?>()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        if (request.getAttribute(VALIDATED_CREDENTIAL) == true || isAuthenticated()) {
            refundAuthenticationReservation(request)
            filterChain.doFilter(request, response)
            return
        }
        // Skip if rate limiting is disabled
        if (!config.enabled) {
            filterChain.doFilter(request, response)
            return
        }

        val requestPath = request.requestURI
        val clientIp = extractClientIp(request)

        // Check if path is whitelisted
        if (isPathWhitelisted(requestPath)) {
            log.debug("Rate limit bypass - whitelisted path: {}", LogSanitizer.sanitizeRequestPath(requestPath))
            filterChain.doFilter(request, response)
            return
        }

        // Check if IP is whitelisted
        if (isIpWhitelisted(clientIp)) {
            log.debug("Rate limit bypass - whitelisted IP")
            filterChain.doFilter(request, response)
            return
        }

        // Authenticated and unmarked endpoints are not limited.
        val rateLimitConfig = resolveRateLimitConfig(request) ?: run {
            filterChain.doFilter(request, response)
            return
        }

        val rateLimitKey = generateRateLimitKey(request, clientIp, rateLimitConfig)
        val bucket = getOrCreateBucket(rateLimitKey, rateLimitConfig)
        val probe: ConsumptionProbe = bucket.tryConsumeAndReturnRemaining(1)

        if (probe.isConsumed) {
            filterWithReservation(bucket, request, response, filterChain)
        } else {
            // Rate limited
            handleRateLimited(response, probe, requestPath)
        }
    }

    private fun filterWithReservation(
        bucket: Bucket,
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!preAuthentication) {
            filterChain.doFilter(request, response)
            return
        }
        val reserved = AtomicReference<Bucket?>(bucket)
        request.setAttribute(AUTHENTICATION_RESERVATION, Runnable { reserved.getAndSet(null)?.addTokens(1) })
        var completed = false
        try {
            filterChain.doFilter(request, response)
            completed = true
        } finally {
            if (completed && response.status < HttpServletResponse.SC_BAD_REQUEST) refundAuthenticationReservation(request)
            request.removeAttribute(AUTHENTICATION_RESERVATION)
        }
    }

    /**
     * Extracts the real client IP from the request.
     * Uses rightmost-non-trusted-proxy strategy to prevent XFF spoofing.
     *
     * X-Forwarded-For format: "client, proxy1, proxy2"
     * The rightmost IP is added by the closest proxy (most trusted).
     * We walk right-to-left, skipping trusted proxy IPs, and return the first
     * non-trusted IP as the real client IP.
     */
    private fun extractClientIp(request: HttpServletRequest): String {
        return ClientIpResolver.resolveWithTrustedRanges(
            request = request,
            trustProxyHeaders = config.trustProxyHeaders,
            clientIpHeader = config.clientIpHeader,
            clientIpHeaderFallback = config.clientIpHeaderFallback,
            trustedProxyRanges = trustedProxyRanges,
        )
    }

    /**
     * Checks if the given path is in the whitelist.
     */
    private fun isPathWhitelisted(path: String): Boolean {
        return path in config.whitelistedPaths
    }

    /**
     * Checks if the given IP is in the whitelist.
     * Supports both individual IPs and CIDR notation.
     */
    private val allWhitelistedIps: List<String> = config.whitelistedIps +
            if (config.whitelistPrivateNetworks) PRIVATE_IP_RANGES else emptyList()

    /**
     * Non-CIDR whitelist entries that parse as IP literals, held as their address bytes.
     *
     * These cannot be compared as text. One address has many spellings, and the servlet
     * container picks a different one than a human writing config does: a request over IPv6
     * loopback arrives as `0:0:0:0:0:0:0:1`, while `rate-limit.yaml` writes the same address
     * `::1`. A string compare rejects that, so every IPv6 whitelist entry silently does
     * nothing — the address bytes are what actually identify the client.
     */
    private val whitelistedAddressBytes: List<ByteArray> = allWhitelistedIps
        .filterNot { it.contains("/") }
        .mapNotNull { literalAddressBytes(it) }

    /** Entries that are neither CIDR nor parseable IP literals keep the old exact-text compare. */
    private val whitelistedLiterals: List<String> = allWhitelistedIps
        .filterNot { it.contains("/") }
        .filter { literalAddressBytes(it) == null }

    private val whitelistedCidrs: List<String> = allWhitelistedIps.filter { it.contains("/") }

    private fun isIpWhitelisted(ip: String): Boolean {
        if (whitelistedLiterals.any { it == ip }) return true
        if (whitelistedCidrs.any { isIpInCidr(ip, it) }) return true
        val addressBytes = literalAddressBytes(ip) ?: return false
        return whitelistedAddressBytes.any { it.contentEquals(addressBytes) }
    }

    /**
     * Checks if an IP address falls within a CIDR range.
     */
    // reason: boundary catch — malformed CIDR config must not break IP whitelist checks regardless of failure type
    @Suppress("TooGenericExceptionCaught")
    private fun isIpInCidr(ip: String, cidr: String): Boolean {
        return try {
            cidrCache.computeIfAbsent(cidr) { IpAddressMatcher(it) }.matches(ip)
        } catch (e: Exception) {
            log.warn("Invalid CIDR notation: {}", cidr, e)
            false
        }
    }

    /**
     * Address bytes for an IP literal, or null if the string is not one.
     *
     * Deliberately literal-only: [InetAddress.getByName] would treat a typo in
     * `whitelisted-ips` as a hostname and perform a DNS lookup, so a bad config entry could
     * resolve to a real address and whitelist a stranger.
     */
    private fun literalAddressBytes(value: String): ByteArray? = try {
        InetAddresses.forString(normalizeIpLiteral(value)).address
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Strips the two decorations a servlet container can put on an IPv6 address before the
     * bytes can be read: surrounding brackets (`[::1]`) and a scope/zone suffix (`::1%lo0`).
     * Guava rejects both, and either one would silently drop the address out of the whitelist.
     */
    private fun normalizeIpLiteral(value: String): String {
        val unbracketed = if (value.startsWith("[") && value.endsWith("]")) {
            value.substring(1, value.length - 1)
        } else {
            value
        }
        return unbracketed.substringBefore('%')
    }

    /** The pre-authentication tier, or null when this request is not rate limited. */
    private fun resolveRateLimitConfig(request: HttpServletRequest): ResolvedRateLimitConfig? {
        if (preAuthentication) return tier(config.authRequestsPerMinute, 0, RateLimitKeyStrategy.IP)
        val annotation = getEndpointAnnotation(request) ?: return null
        val limit = annotation.requestsPerMinute.takeIf { it > 0 } ?: when (annotation.type) {
            RateLimitType.AUTH -> config.authRequestsPerMinute
            RateLimitType.READ -> config.readRequestsPerMinute
            RateLimitType.WRITE -> config.writeRequestsPerMinute
        }
        return tier(limit, annotation.burstCapacity, annotation.keyStrategy)
    }

    private fun tier(limit: Long, burstCapacity: Long, keyStrategy: RateLimitKeyStrategy) = ResolvedRateLimitConfig(
        limit = limit,
        burstCapacity = burstCapacity.takeIf { it > 0 } ?: (limit * config.burstCapacityMultiplier).toLong(),
        keyStrategy = keyStrategy,
    )

    /**
     * Gets the @RateLimit annotation from the endpoint handler, if present.
     */
    // reason: boundary catch — handler resolution failure must fall back to default limits, not break the filter
    @Suppress("TooGenericExceptionCaught")
    private fun getEndpointAnnotation(request: HttpServletRequest): RateLimit? {
        val handlerMapping = handlerMapping ?: return null

        return try {
            val handler = handlerMapping.getHandler(request)?.handler as? HandlerMethod ?: return null
            annotationCache.computeIfAbsent(handler) { it.getMethodAnnotation(RateLimit::class.java) }
        } catch (e: Exception) {
            log.debug("Could not resolve handler for rate limit annotation: {}", e.message)
            null
        }
    }

    /**
     * Generates a unique key for rate limiting.
     */
    private fun generateRateLimitKey(
        request: HttpServletRequest,
        clientIp: String,
        config: ResolvedRateLimitConfig
    ): String {
        val subject = if (preAuthentication) RateLimitSubject(null, null) else RateLimitSubject.from(request)
        val clientRef = LogSanitizer.stableFingerprint(clientIp, prefix = "ip")
        val keyPart = subject.quotaKey(config.keyStrategy, clientRef)
        return "$KEY_PREFIX${if (preAuthentication) "preauth:" else ""}${config.keyStrategy}:${config.limit}:${config.burstCapacity}:$keyPart"
    }


    /** A request that passed authentication never counts as a failed credential attempt. */
    private fun isAuthenticated(): Boolean = SecurityContextHolder.getContext().authentication
        ?.let { it.isAuthenticated && it !is AnonymousAuthenticationToken } == true

    /**
     * Gets or creates a rate limit bucket for the given key.
     */
    private fun getOrCreateBucket(key: String, config: ResolvedRateLimitConfig): Bucket {
        val bucketConfiguration = createBucketConfiguration(config)

        return proxyManager.builder()
            .build(key, Supplier { bucketConfiguration })
    }

    /**
     * Creates a Bucket4j configuration for the given rate limit settings.
     */
    private fun createBucketConfiguration(config: ResolvedRateLimitConfig): BucketConfiguration {
        val refillRate = config.limit // tokens per minute
        val capacity = config.burstCapacity

        return BucketConfiguration.builder()
            .addLimit(
                Bandwidth.builder()
                    .capacity(capacity)
                    .refillGreedy(refillRate, Duration.ofMinutes(1))
                    .build()
            )
            .build()
    }

    /**
     * Handles a rate-limited request.
     */
    private fun handleRateLimited(
        response: HttpServletResponse,
        probe: ConsumptionProbe,
        path: String
    ) {
        val retryAfterSeconds = (probe.nanosToWaitForRefill / 1_000_000_000) + 1

        log.warn(
            "Rate limit exceeded - IP: {}, Path: {}, Wait: {} seconds",
            ClientIpRecord.WITHHELD,
            LogSanitizer.sanitizeRequestPath(path),
            retryAfterSeconds
        )

        response.setHeader(HEADER_RETRY_AFTER, retryAfterSeconds.toString())

        writeFilterError(response, HttpStatus.TOO_MANY_REQUESTS.value(),
            "Rate limit exceeded. Please wait $retryAfterSeconds seconds before retrying.", retryAfterSeconds = retryAfterSeconds)
    }

    /**
     * Resolved rate limit configuration for a specific request.
     */
    private data class ResolvedRateLimitConfig(
        val limit: Long,
        val burstCapacity: Long,
        val keyStrategy: RateLimitKeyStrategy,
    )
}
