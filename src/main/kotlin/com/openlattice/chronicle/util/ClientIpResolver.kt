package com.openlattice.chronicle.util

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.security.web.util.matcher.IpAddressMatcher
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized, fail-closed client IP resolution.
 *
 * Proxy headers are attacker-controlled unless the direct peer is a trusted
 * proxy. Keep trusted proxy CIDRs narrow; do not use broad private ranges.
 */
public object ClientIpResolver {
    private val log = LoggerFactory.getLogger(ClientIpResolver::class.java)
    private val ipLiteralPattern = Regex("^[0-9a-fA-F:.]+$")
    private val trustedProxyRangeCache = ConcurrentHashMap<String, List<IpAddressMatcher>>()

    /** The deployment's proxy subnets, comma separated; the selfhost bundle sets it to web's one address on its internal core network. */
    public const val TRUSTED_PROXY_CIDRS_ENV: String = "CHRONICLE_TRUSTED_PROXY_CIDRS"

    /**
     * Trusted when the deployment names no proxy: only a reverse proxy on this host. The one owner of that
     * default; `RateLimitConfiguration` and the shipped rate-limit.yaml use it too, so the rate limiter and every
     * audit or log IP reference believe the same forwarded headers.
     */
    public val LOOPBACK_PROXY_CIDRS: List<String> = listOf("127.0.0.0/8", "::1/128")

    private val defaultTrustedProxyCidrs: List<String> = deploymentTrustedProxyCidrs(LOOPBACK_PROXY_CIDRS)

    public fun deploymentTrustedProxyCidrs(
        fallback: List<String>,
        deployment: String? = System.getenv(TRUSTED_PROXY_CIDRS_ENV),
    ): List<String> = deployment?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: fallback

    public fun resolve(
        request: HttpServletRequest,
        trustProxyHeaders: Boolean = true,
        clientIpHeader: String = "X-Forwarded-For",
        clientIpHeaderFallback: String = "X-Real-IP",
        trustedProxyCidrs: List<String> = defaultTrustedProxyCidrs,
    ): String {
        return resolveWithTrustedRanges(
            request = request,
            trustProxyHeaders = trustProxyHeaders,
            clientIpHeader = clientIpHeader,
            clientIpHeaderFallback = clientIpHeaderFallback,
            trustedProxyRanges = parseTrustedProxyCidrs(trustedProxyCidrs),
        )
    }

    // reason: fail-closed IP resolution uses guard-clause early returns and a reverse-chain walk
    // with skip/accept jumps; collapsing them would obscure the security logic and risk changing
    // which address is trusted
    @Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
    public fun resolveWithTrustedRanges(
        request: HttpServletRequest,
        trustProxyHeaders: Boolean,
        clientIpHeader: String,
        clientIpHeaderFallback: String,
        trustedProxyRanges: List<IpAddressMatcher>,
    ): String {
        val remoteAddr = request.remoteAddr ?: UNKNOWN_CLIENT_IP
        if (!trustProxyHeaders) {
            return remoteAddr
        }

        if (!trustedProxyRanges.matchesAny(remoteAddr)) {
            return remoteAddr
        }

        val forwardedFor = request.getHeader(clientIpHeader)
        if (!forwardedFor.isNullOrBlank()) {
            val chain = forwardedFor.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .plus(remoteAddr)

            for (ip in chain.asReversed()) {
                if (!isValidIpLiteral(ip)) {
                    log.warn("Rejected proxy client IP entry with invalid characters")
                    continue
                }
                if (trustedProxyRanges.matchesAny(ip)) {
                    continue
                }
                return ip
            }
        }

        val fallback = request.getHeader(clientIpHeaderFallback)?.trim()
        if (!fallback.isNullOrBlank() &&
            isValidIpLiteral(fallback) &&
            !trustedProxyRanges.matchesAny(fallback)
        ) {
            return fallback
        }

        return remoteAddr
    }

    public fun parseTrustedProxyCidrs(cidrs: List<String>): List<IpAddressMatcher> {
        val cacheKey = cidrs.joinToString(",")
        return trustedProxyRangeCache.computeIfAbsent(cacheKey) {
            cidrs.mapNotNull(::parseTrustedProxyCidr)
        }
    }

    private fun parseTrustedProxyCidr(cidr: String): IpAddressMatcher? {
        return try {
            IpAddressMatcher(cidr)
        } catch (e: IllegalArgumentException) {
            log.warn("Ignoring invalid trusted proxy CIDR: {}", cidr, e)
            null
        }
    }

    /** The matcher rejects anything but an address literal, so non-literals simply match no range. */
    private fun List<IpAddressMatcher>.matchesAny(ip: String): Boolean = isValidIpLiteral(ip) && any { it.matches(ip) }

    private fun isValidIpLiteral(value: String): Boolean {
        if (value.length > MAX_IP_LITERAL_LENGTH || !ipLiteralPattern.matches(value)) {
            return false
        }
        return try {
            InetAddress.getByName(value)
            true
        } catch (_: Exception) {
            false
        }
    }

    private const val MAX_IP_LITERAL_LENGTH = 45
    private const val UNKNOWN_CLIENT_IP = "0.0.0.0"
}
