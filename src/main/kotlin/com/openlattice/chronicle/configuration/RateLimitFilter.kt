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
import com.openlattice.chronicle.util.ClientIpResolver
import com.openlattice.chronicle.util.LogSanitizer
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.ConsumptionProbe
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.grid.hazelcast.HazelcastProxyManager
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/**
 * Distributed rate limiting filter using Bucket4j with Hazelcast backend.
 *
 * This filter provides:
 * - Global rate limiting per IP (for unauthenticated) or per user (for authenticated)
 * - Endpoint-specific rate limits via @RateLimit annotation
 * - Stricter limits for authentication endpoints
 * - IP/path whitelisting for internal services and health checks
 * - Rate limit response headers (X-RateLimit-*)
 * - Retry-After header for rate-limited responses
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
    private val handlerMapping: RequestMappingHandlerMapping?
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(RateLimitFilter::class.java)

    internal companion object {

        // Response headers
        public const val HEADER_LIMIT = "X-RateLimit-Limit"
        public const val HEADER_REMAINING = "X-RateLimit-Remaining"
        public const val HEADER_RESET = "X-RateLimit-Reset"
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
    private val proxyManager: ProxyManager<String> = HazelcastProxyManager<String>(bucketMap)

    // Cache for parsed CIDR ranges
    private val cidrCache = ConcurrentHashMap<String, CidrRange>()

    // Pre-parsed trusted proxy CIDR ranges for hot-path client IP extraction.
    private val trustedProxyRanges: List<CidrRange> = ClientIpResolver.parseTrustedProxyCidrs(config.trustedProxyCidrs)

    // Cache for endpoint rate limit annotations
    private val annotationCache = ConcurrentHashMap<String, RateLimit?>()

    // Regex to extract study ID from URL paths like /chronicle/v3/study/{uuid}/...
    // and /chronicle/v4/study/{uuid}/...
    private val studyIdPattern = Regex("/study/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})")

    // Regex to extract participant ID from URL paths like .../participant/{participantId}/...
    private val participantIdPattern = Regex("/participant/([^/]+)")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
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
            log.debug("Rate limit bypass - whitelisted IP: {}", clientIp)
            filterChain.doFilter(request, response)
            return
        }

        // Get rate limit configuration for this endpoint
        val rateLimitConfig = resolveRateLimitConfig(request, requestPath)

        // Check for bypass annotation
        if (rateLimitConfig.bypass) {
            log.debug("Rate limit bypass - annotation bypass for: {}", LogSanitizer.sanitizeRequestPath(requestPath))
            filterChain.doFilter(request, response)
            return
        }

        // Generate rate limit key
        val rateLimitKey = generateRateLimitKey(request, clientIp, rateLimitConfig)

        // Get or create bucket for this key
        val bucket = getOrCreateBucket(rateLimitKey, rateLimitConfig)

        // Try to consume a token
        val probe: ConsumptionProbe = bucket.tryConsumeAndReturnRemaining(1)

        // Add rate limit headers
        if (config.includeHeaders) {
            addRateLimitHeaders(response, probe, rateLimitConfig.limit)
        }

        if (probe.isConsumed) {
            // Request allowed - proceed
            filterChain.doFilter(request, response)
        } else {
            // Rate limited
            handleRateLimited(response, probe, clientIp, requestPath)
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
            val range = cidrCache.computeIfAbsent(cidr) { CidrRange.parse(it) }
            range.contains(ip)
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

    /**
     * Resolves the rate limit configuration for the current request.
     * Checks for @RateLimit annotation on method/class, falls back to config defaults.
     *
     * Tiered limits:
     * - HEALTH: 1000/min (health/status endpoints)
     * - READ: 100/min per study (GET data endpoints)
     * - WRITE: 60/min per participant per study (POST upload endpoints)
     * - ADMIN: 20/min (administrative endpoints)
     * - AUTH: 10/min per IP (authentication, prevents brute force)
     * - SENSITIVE: 20/min (exports, bulk operations)
     * - DEFAULT: 100/min (everything else)
     */
    // reason: security rate-limit tier resolution — branch-by-branch precedence is load-bearing; restructuring risks altering enforced limits
    @Suppress("CyclomaticComplexMethod")
    private fun resolveRateLimitConfig(request: HttpServletRequest, path: String): ResolvedRateLimitConfig {
        // Try to get annotation from handler method
        val annotation = getEndpointAnnotation(request)

        // Check for bypass
        if (annotation?.bypass == true || annotation?.type == RateLimitType.UNLIMITED) {
            return ResolvedRateLimitConfig(
                limit = Long.MAX_VALUE,
                burstCapacity = Long.MAX_VALUE,
                keyStrategy = RateLimitKeyStrategy.IP,
                bypass = true
            )
        }

        // Determine the effective rate limit type
        val effectiveType = when {
            annotation != null && annotation.requestsPerMinute > 0 -> null // explicit override
            annotation != null -> annotation.type
            isAuthPath(path) -> RateLimitType.AUTH
            else -> RateLimitType.DEFAULT
        }

        // Determine base rate limit, checking for per-study overrides
        val studyId = extractStudyId(path)
        val studyOverrideLimit = if (studyId != null) config.studyOverrides[studyId] else null

        val baseLimit = when {
            annotation != null && annotation.requestsPerMinute > 0 -> annotation.requestsPerMinute
            studyOverrideLimit != null -> studyOverrideLimit
            effectiveType == RateLimitType.HEALTH -> config.healthRequestsPerMinute
            effectiveType == RateLimitType.READ -> config.readRequestsPerMinute
            effectiveType == RateLimitType.WRITE -> config.writeRequestsPerMinute
            effectiveType == RateLimitType.ADMIN -> config.adminRequestsPerMinute
            effectiveType == RateLimitType.AUTH -> config.authRequestsPerMinute
            effectiveType == RateLimitType.SENSITIVE -> config.sensitiveRequestsPerMinute
            else -> config.defaultRequestsPerMinute
        }

        // Determine burst capacity
        val burstCapacity = when {
            annotation != null && annotation.burstCapacity > 0 -> annotation.burstCapacity
            else -> (baseLimit * config.burstCapacityMultiplier).toLong()
        }

        // Determine key strategy — annotation takes precedence, then type-based defaults
        val keyStrategy = when {
            annotation != null && annotation.keyStrategy != RateLimitKeyStrategy.AUTO -> annotation.keyStrategy
            effectiveType == RateLimitType.WRITE -> RateLimitKeyStrategy.PARTICIPANT_STUDY
            effectiveType == RateLimitType.READ -> RateLimitKeyStrategy.STUDY
            effectiveType == RateLimitType.AUTH -> RateLimitKeyStrategy.IP
            effectiveType == RateLimitType.ADMIN -> RateLimitKeyStrategy.USER
            else -> annotation?.keyStrategy ?: RateLimitKeyStrategy.AUTO
        }

        return ResolvedRateLimitConfig(
            limit = baseLimit,
            burstCapacity = burstCapacity,
            keyStrategy = keyStrategy,
            bypass = false
        )
    }

    /**
     * Checks if the path is an authentication endpoint.
     */
    private fun isAuthPath(path: String): Boolean {
        return config.authPaths.any { authPath ->
            path.startsWith(authPath)
        }
    }

    /**
     * Normalizes a URI for handler-annotation cache keys without retaining request identifiers.
     */
    private fun normalizeCacheKey(uri: String): String = LogSanitizer.sanitizeRequestPath(uri)

    /**
     * Gets the @RateLimit annotation from the endpoint handler, if present.
     */
    // reason: boundary catch — handler resolution failure must fall back to default limits, not break the filter
    @Suppress("TooGenericExceptionCaught")
    private fun getEndpointAnnotation(request: HttpServletRequest): RateLimit? {
        if (handlerMapping == null) {
            return null
        }

        val cacheKey = "${request.method}:${normalizeCacheKey(request.requestURI)}"

        return annotationCache.computeIfAbsent(cacheKey) {
            try {
                val handlerExecutionChain = handlerMapping.getHandler(request)
                val handler = handlerExecutionChain?.handler

                if (handler is HandlerMethod) {
                    // Check method-level annotation first
                    handler.getMethodAnnotation(RateLimit::class.java)
                        ?: handler.beanType.getAnnotation(RateLimit::class.java)
                } else {
                    null
                }
            } catch (e: Exception) {
                log.debug("Could not resolve handler for rate limit annotation: {}", e.message)
                null
            }
        }
    }

    /**
     * Generates a unique key for rate limiting.
     */
    // reason: security rate-limit key strategy — each branch maps to a distinct bucketing scheme; restructuring risks cross-contaminating buckets
    @Suppress("CyclomaticComplexMethod")
    private fun generateRateLimitKey(
        request: HttpServletRequest,
        clientIp: String,
        config: ResolvedRateLimitConfig
    ): String {
        val userId = getAuthenticatedUserId()
        val path = request.requestURI
        val clientRef = LogSanitizer.stableFingerprint(clientIp, prefix = "ip")
        val userRef = userId?.let { LogSanitizer.stableFingerprint(it, prefix = "user") }

        val keyPart = when (config.keyStrategy) {
            RateLimitKeyStrategy.IP -> clientRef
            RateLimitKeyStrategy.USER -> userRef ?: clientRef
            RateLimitKeyStrategy.USER_AND_IP -> "user_ip:${userRef ?: "user:anon"}:$clientRef"
            RateLimitKeyStrategy.ENDPOINT -> "endpoint:${LogSanitizer.sanitizeRequestPath(path)}:${userRef ?: clientRef}"
            RateLimitKeyStrategy.STUDY -> {
                val studyId = extractStudyId(path) ?: "unknown"
                "study:${stableRefOrUnknown(studyId, "study")}:${userRef ?: clientRef}"
            }
            RateLimitKeyStrategy.PARTICIPANT_STUDY -> {
                val studyId = extractStudyId(path) ?: "unknown"
                val participantId = extractParticipantId(path) ?: "unknown"
                // Include the authenticated user or client IP so an unauthenticated attacker who guesses a
                // participant ID cannot exhaust that participant's upload quota before authentication runs.
                "participant:${stableRefOrUnknown(studyId, "study")}:" +
                    "${stableRefOrUnknown(participantId, "participant")}:${userRef ?: clientRef}"
            }
            RateLimitKeyStrategy.AUTO -> {
                userRef ?: clientRef
            }
        }

        return "$KEY_PREFIX$keyPart"
    }

    private fun stableRefOrUnknown(value: String, prefix: String): String {
        return if (value == "unknown") {
            "$prefix:unknown"
        } else {
            LogSanitizer.stableFingerprint(value, prefix = prefix)
        }
    }

    /**
     * Extracts study UUID from the request path.
     * Matches patterns like /chronicle/v3/study/{uuid} or /chronicle/v4/study/{uuid}
     */
    private fun extractStudyId(path: String): String? {
        return studyIdPattern.find(path)?.groupValues?.get(1)
    }

    /**
     * Extracts participant ID from the request path.
     * Matches patterns like .../participant/{participantId}/...
     */
    private fun extractParticipantId(path: String): String? {
        return participantIdPattern.find(path)?.groupValues?.get(1)
    }

    /**
     * Gets the authenticated user ID from the security context.
     */
    // reason: boundary catch — auth lookup must never break the rate-limit filter regardless of failure type
    @Suppress("TooGenericExceptionCaught")
    private fun getAuthenticatedUserId(): String? {
        return try {
            val authentication = SecurityContextHolder.getContext().authentication
            if (authentication != null && authentication.isAuthenticated) {
                resolvePrincipalUserId(authentication.principal, authentication.name)
            } else {
                null
            }
        } catch (e: Exception) {
            log.debug("Could not resolve authenticated user id for rate limiting: {}", e.message)
            null
        }
    }

    private fun resolvePrincipalUserId(principal: Any?, name: String?): String? {
        return when (principal) {
            is String -> if (principal != "anonymousUser") principal else null
            else -> name?.takeIf { it != "anonymousUser" }
        }
    }

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
     * Adds rate limit headers to the response.
     */
    private fun addRateLimitHeaders(
        response: HttpServletResponse,
        probe: ConsumptionProbe,
        limit: Long
    ) {
        response.setHeader(HEADER_LIMIT, limit.toString())
        response.setHeader(HEADER_REMAINING, probe.remainingTokens.toString())

        // Reset time in seconds since epoch
        val resetSeconds = System.currentTimeMillis() / 1000 + (probe.nanosToWaitForRefill / 1_000_000_000)
        response.setHeader(HEADER_RESET, resetSeconds.toString())
    }

    /**
     * Handles a rate-limited request.
     */
    private fun handleRateLimited(
        response: HttpServletResponse,
        probe: ConsumptionProbe,
        clientIp: String,
        path: String
    ) {
        val retryAfterSeconds = (probe.nanosToWaitForRefill / 1_000_000_000) + 1

        log.warn(
            "Rate limit exceeded - IP: {}, Path: {}, Wait: {} seconds",
            LogSanitizer.sanitizeIp(clientIp),
            LogSanitizer.sanitizeRequestPath(path),
            retryAfterSeconds
        )

        if (config.includeRetryAfter) {
            response.setHeader(HEADER_RETRY_AFTER, retryAfterSeconds.toString())
        }

        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.contentType = "application/json"
        response.writer.write(
            """{
                "error": "Rate limit exceeded",
                "status": 429,
                "retryAfter": $retryAfterSeconds,
                "message": "Too many requests. Please wait $retryAfterSeconds seconds before retrying."
            }""".trimIndent()
        )
        response.writer.flush()
    }

    /**
     * Resolved rate limit configuration for a specific request.
     */
    private data class ResolvedRateLimitConfig(
        val limit: Long,
        val burstCapacity: Long,
        val keyStrategy: RateLimitKeyStrategy,
        val bypass: Boolean
    )
}

/**
 * Utility class for CIDR range parsing and IP matching.
 * Supports both IPv4 and IPv6 using byte-array comparison with prefix masking.
 */
public open class CidrRange private constructor(
    private val networkBytes: ByteArray,
    private val prefixLength: Int
) {
    internal companion object {
        public fun parse(cidr: String): CidrRange {
            val parts = cidr.split("/")
            require(parts.size == 2) { "Invalid CIDR notation: $cidr" }

            val ip = parts[0]
            val prefixLength = parts[1].toInt()
            val address = InetAddress.getByName(ip)
            val addrBytes = address.address
            val maxPrefix = addrBytes.size * 8

            require(prefixLength in 0..maxPrefix) {
                "Invalid prefix length $prefixLength for ${if (addrBytes.size == 4) "IPv4" else "IPv6"}"
            }

            // Mask the network address to the prefix
            val masked = maskBytes(addrBytes, prefixLength)
            return CidrRange(masked, prefixLength)
        }

        private fun maskBytes(bytes: ByteArray, prefixLength: Int): ByteArray {
            val masked = bytes.copyOf()
            var remainingBits = prefixLength
            for (i in masked.indices) {
                if (remainingBits >= 8) {
                    remainingBits -= 8
                } else if (remainingBits > 0) {
                    val maskByte = (0xFF shl (8 - remainingBits)) and 0xFF
                    masked[i] = (masked[i].toInt() and maskByte).toByte()
                    remainingBits = 0
                } else {
                    masked[i] = 0
                }
            }
            return masked
        }
    }

    public fun contains(ip: String): Boolean {
        val address = try {
            InetAddress.getByName(ip)
        } catch (_: Exception) {
            return false
        }

        val ipBytes = address.address

        // IPv4 (4 bytes) vs IPv6 (16 bytes) must match
        if (ipBytes.size != networkBytes.size) {
            return false
        }

        val maskedIp = maskBytes(ipBytes, prefixLength)
        return maskedIp.contentEquals(networkBytes)
    }
}
