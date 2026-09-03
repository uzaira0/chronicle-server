package com.openlattice.chronicle.util

import com.openlattice.chronicle.configuration.CidrRange
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
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
    private val trustedProxyRangeCache = ConcurrentHashMap<String, List<CidrRange>>()

    private val defaultTrustedProxyCidrs: List<String> =
        (System.getenv("CHRONICLE_TRUSTED_PROXY_CIDRS") ?: "172.16.0.0/12,127.0.0.0/8,::1/128")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

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
        trustProxyHeaders: Boolean = true,
        clientIpHeader: String = "X-Forwarded-For",
        clientIpHeaderFallback: String = "X-Real-IP",
        trustedProxyRanges: List<CidrRange>,
    ): String {
        val remoteAddr = request.remoteAddr ?: UNKNOWN_CLIENT_IP
        if (!trustProxyHeaders) {
            return remoteAddr
        }

        if (trustedProxyRanges.none { it.contains(remoteAddr) }) {
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
                if (trustedProxyRanges.any { it.contains(ip) }) {
                    continue
                }
                return ip
            }
        }

        val fallback = request.getHeader(clientIpHeaderFallback)?.trim()
        if (!fallback.isNullOrBlank() &&
            isValidIpLiteral(fallback) &&
            trustedProxyRanges.none { it.contains(fallback) }
        ) {
            return fallback
        }

        return remoteAddr
    }

    public fun parseTrustedProxyCidrs(cidrs: List<String>): List<CidrRange> {
        val cacheKey = cidrs.joinToString(",")
        return trustedProxyRangeCache.computeIfAbsent(cacheKey) {
            cidrs.mapNotNull(::parseTrustedProxyCidr)
        }
    }

    // reason: boundary catch — CidrRange.parse can throw IllegalArgumentException /
    // NumberFormatException / UnknownHostException; any malformed CIDR is logged and skipped
    @Suppress("TooGenericExceptionCaught")
    private fun parseTrustedProxyCidr(cidr: String): CidrRange? {
        return try {
            CidrRange.parse(cidr)
        } catch (e: Exception) {
            log.warn("Ignoring invalid trusted proxy CIDR: {}", cidr, e)
            null
        }
    }

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
