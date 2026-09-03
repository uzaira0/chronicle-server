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

/**
 * Exception thrown when a Server-Side Request Forgery (SSRF) attack is detected.
 *
 * This exception is thrown by [SsrfValidator] and [SafeHttpClientFactory] when an
 * outbound HTTP request violates SSRF protection rules.
 *
 * @property violationType The type of SSRF violation that was detected
 * @property targetUrl The URL that triggered the violation (sanitized for logging)
 * @property message Human-readable description of the violation
 */
public open class SsrfException(
    public val violationType: SsrfViolationType,
    public val targetUrl: String,
    message: String
) : SecurityException(message) {

    internal companion object {
        /**
         * Creates an exception for a disallowed host.
         */
        public fun disallowedHost(host: String): SsrfException {
            return SsrfException(
                violationType = SsrfViolationType.DISALLOWED_HOST,
                targetUrl = sanitizeForLogging(host),
                message = "SSRF protection: Host not in allowlist"
            )
        }

        /**
         * Creates an exception for a disallowed protocol.
         */
        public fun disallowedProtocol(url: String, protocol: String): SsrfException {
            return SsrfException(
                violationType = SsrfViolationType.DISALLOWED_PROTOCOL,
                targetUrl = sanitizeForLogging(url),
                message = "SSRF protection: Protocol '$protocol' not allowed"
            )
        }

        /**
         * Creates an exception for a private IP address.
         */
        // reason: caller (SsrfValidator) passes the offending IP; kept in the factory API for
        // call-site symmetry/forensics, but deliberately excluded from the message to avoid
        // leaking internal addresses in security exceptions
        @Suppress("UnusedParameter")
        public fun privateIpAddress(url: String, ip: String): SsrfException {
            return SsrfException(
                violationType = SsrfViolationType.PRIVATE_IP,
                targetUrl = sanitizeForLogging(url),
                message = "SSRF protection: Private IP address blocked"
            )
        }

        /**
         * Creates an exception for localhost access.
         */
        public fun localhostBlocked(url: String): SsrfException {
            return SsrfException(
                violationType = SsrfViolationType.LOCALHOST,
                targetUrl = sanitizeForLogging(url),
                message = "SSRF protection: Localhost access blocked"
            )
        }

        /**
         * Creates an exception for link-local address.
         */
        // reason: caller (SsrfValidator) passes the offending IP; kept in the factory API for
        // call-site symmetry/forensics, but deliberately excluded from the message to avoid
        // leaking internal addresses in security exceptions
        @Suppress("UnusedParameter")
        public fun linkLocalBlocked(url: String, ip: String): SsrfException {
            return SsrfException(
                violationType = SsrfViolationType.LINK_LOCAL,
                targetUrl = sanitizeForLogging(url),
                message = "SSRF protection: Link-local address blocked"
            )
        }

        /**
         * Creates an exception for cloud metadata endpoint access.
         */
        public fun metadataEndpointBlocked(url: String): SsrfException {
            return SsrfException(
                violationType = SsrfViolationType.METADATA_ENDPOINT,
                targetUrl = sanitizeForLogging(url),
                message = "SSRF protection: Cloud metadata endpoint blocked"
            )
        }

        /**
         * Creates an exception for exceeding redirect limit.
         */
        public fun tooManyRedirects(url: String, count: Int): SsrfException {
            return SsrfException(
                violationType = SsrfViolationType.TOO_MANY_REDIRECTS,
                targetUrl = sanitizeForLogging(url),
                message = "SSRF protection: Redirect limit exceeded ($count redirects)"
            )
        }

        /**
         * Creates an exception for DNS rebinding detection.
         */
        // reason: original/resolved IPs are part of the factory API for forensics, but
        // deliberately excluded from the message to avoid leaking internal addresses
        @Suppress("UnusedParameter")
        public fun dnsRebindingDetected(url: String, originalIp: String, resolvedIp: String): SsrfException {
            return SsrfException(
                violationType = SsrfViolationType.DNS_REBINDING,
                targetUrl = sanitizeForLogging(url),
                message = "SSRF protection: DNS rebinding detected"
            )
        }

        /**
         * Creates an exception for a transient DNS lookup failure.
         */
        public fun dnsResolutionFailed(host: String): SsrfException {
            return SsrfException(
                violationType = SsrfViolationType.DNS_RESOLUTION_FAILED,
                targetUrl = sanitizeForLogging(host),
                message = "SSRF protection: Hostname resolution failed"
            )
        }

        /**
         * Creates an exception for invalid URL format.
         */
        public fun invalidUrl(url: String, reason: String): SsrfException {
            return SsrfException(
                violationType = SsrfViolationType.INVALID_URL,
                targetUrl = sanitizeForLogging(url),
                message = "SSRF protection: Invalid URL - $reason"
            )
        }

        /**
         * Sanitizes a URL or host for safe logging.
         * Removes potentially sensitive information and truncates long URLs.
         */
        private fun sanitizeForLogging(value: String): String {
            val maxLength = 100
            val sanitized = value
                .replace(Regex("[\\r\\n\\t]"), " ")  // Remove newlines/tabs
                .replace(Regex("\\x00"), "")         // Remove null bytes
                .take(maxLength)
            return if (value.length > maxLength) "$sanitized..." else sanitized
        }
    }
}

/**
 * Types of SSRF violations that can be detected.
 */
public enum class SsrfViolationType {
    /** Host is not in the allowed hosts list */
    DISALLOWED_HOST,

    /** Protocol is not allowed (e.g., file://, gopher://) */
    DISALLOWED_PROTOCOL,

    /** Target resolves to a private IP address */
    PRIVATE_IP,

    /** Target is localhost (127.0.0.1, ::1) */
    LOCALHOST,

    /** Target is a link-local address (169.254.x.x, fe80::) */
    LINK_LOCAL,

    /** Target is a cloud metadata endpoint (169.254.169.254) */
    METADATA_ENDPOINT,

    /** Redirect limit exceeded */
    TOO_MANY_REDIRECTS,

    /** DNS resolved to different IP than expected (rebinding attack) */
    DNS_REBINDING,

    /** DNS did not return an address; callers may retry without weakening SSRF checks */
    DNS_RESOLUTION_FAILED,

    /** URL format is invalid or malformed */
    INVALID_URL
}
