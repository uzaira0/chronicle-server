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

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.audit.AuditAction
import com.openlattice.chronicle.audit.AuditLogEntryBuilder
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.filters.MobileApiHmacAuthenticationToken
import com.openlattice.chronicle.filters.MobileEnrollmentAuthenticationToken
import com.openlattice.chronicle.filters.MobileReviewerAuthenticationToken
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.util.ClientIpResolver
import com.openlattice.chronicle.util.LogSanitizer
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.zip.InflaterInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/**
 * HMAC Request Signing and Replay Prevention Filter for Mobile API endpoints.
 *
 * This filter provides two critical security measures for the /chronicle/v3/study/ and /chronicle/v4/study/ endpoints:
 *
 * 1. REQUEST SIGNING (HMAC-SHA256):
 *    - Ensures request integrity - any tampering with the request will invalidate the signature
 *    - Signs: HTTP method + path + timestamp + nonce + SHA256(body)
 *    - Uses constant-time comparison to prevent timing attacks
 *    - Is not client identity: the shared value is embedded in distributed apps
 *      and must be treated as extractable
 *
 * 2. REPLAY PREVENTION:
 *    - Timestamp validation: Rejects requests older than 5 minutes (with 30s clock skew allowance)
 *    - Nonce tracking: Stores nonces in Hazelcast distributed cache, rejects duplicates
 *    - Nonces expire after TTL to prevent unbounded growth
 *
 * Except at the enrollment credential-bootstrap boundary, mobile identity and
 * authorization come from the enrollment-issued API key, which is independently
 * bound to the study, participant, and device by ApiKeyAuthenticationFilter.
 * A valid global HMAC must never be accepted as a replacement for that key.
 *
 * HEADERS REQUIRED:
 * - X-Chronicle-Signature: Base64-encoded HMAC-SHA256 signature
 * - X-Chronicle-Timestamp: Unix epoch timestamp (seconds)
 * - X-Chronicle-Nonce: UUID to prevent replay attacks
 *
 * BACKWARD COMPATIBILITY:
 * - When signingRequired=false, missing signatures are allowed (logs warning)
 * - When signingRequired=true, all requests must be signed
 *
 * SIGNATURE COMPUTATION:
 * The client must compute the signature as follows:
 * 1. Create the signing string: METHOD|PATH|TIMESTAMP|NONCE|SHA256(BODY)
 * 2. Compute HMAC-SHA256 of the signing string using the shared secret
 * 3. Base64-encode the result
 *
 * Example:
 * ```
 * signingString = "POST|/chronicle/v3/study/abc/participant/xyz/android/upload|1704067200|550e8400-e29b-41d4-a716-446655440000|a3f2b7..."
 * signature = Base64(HMAC-SHA256(signingString, sharedSecret))
 * ```
 *
 * @param hazelcastInstance Hazelcast instance for distributed nonce storage
 * @param signingSecret The current shared secret key for HMAC computation
 * @param signingRequired Whether signature verification is mandatory
 * @param maxRequestAgeMinutes Maximum age of requests in minutes (default: 5)
 * @param clockSkewSeconds Allowed clock skew in seconds (default: 30)
 * @param nonceTtlMinutes TTL for nonces in the cache in minutes (default: 10)
 * @param signatureVerificationEnabled Whether signatures are checked. Body decoding remains active when false.
 * @param maxDecodedBodyBytes Maximum body size after transport decompression.
 * @param previousSigningSecrets Prior shared keys accepted only during a bounded mobile rollout.
 *
 * @author uzaira0
 */
// This is a compatibility boundary assembled by MobileApiSecurityConfig: retaining named constructor
// arguments keeps existing deployments/tests source-compatible while the three credential modes coexist.
@Suppress("LongParameterList")
public open class MobileApiSignatureFilter(
    hazelcastInstance: HazelcastInstance,
    private val signingSecret: String,
    private val signingRequired: Boolean = false,
    private val maxRequestAgeMinutes: Long = 5,
    private val clockSkewSeconds: Long = 30,
    private val nonceTtlMinutes: Long = 10,
    private val signatureVerificationEnabled: Boolean = true,
    private val maxDecodedBodyBytes: Int = SecurityHardeningConfig.MAX_REQUEST_SIZE_BYTES.toInt(),
    private val internalWebSecret: String = "",
    previousSigningSecrets: List<String> = emptyList(),
    private val participantFormAccessService: ParticipantFormAccessService? = null,
    private val reviewerEnrollmentSecret: String = "",
    private val reviewerStudyId: UUID? = null,
    private val auditService: AuditService? = null,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(MobileApiSignatureFilter::class.java)
    private val signingSecrets = (listOf(signingSecret) + previousSigningSecrets)
        .filter(String::isNotBlank)
        .distinct()

    internal companion object {

        // Header names
        public const val HEADER_SIGNATURE = "X-Chronicle-Signature"
        public const val HEADER_TIMESTAMP = "X-Chronicle-Timestamp"
        public const val HEADER_NONCE = "X-Chronicle-Nonce"
        public const val HEADER_ENROLLMENT_CODE = "X-Chronicle-Enrollment-Code"
        public const val HEADER_REVIEWER_SECRET = "X-Chronicle-Reviewer-Secret"

        // Hazelcast map name for nonce storage
        public const val NONCE_CACHE_MAP_NAME = "MOBILE_API_NONCES"

        // Algorithm constants
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val HASH_ALGORITHM = "SHA-256"

        // Signing string delimiter
        private const val DELIMITER = "|"

        // Mobile API path prefixes — must match actual mobile endpoint paths
        private val MOBILE_API_PREFIXES = listOf(
            "/chronicle/v3/study/",
            "/chronicle/v4/study/",
            "/chronicle/v4/mobile/",
        )

        private const val INTERNAL_WEB_HEADER = "X-Chronicle-Internal-Web"
        private const val API_KEY_HEADER = "X-Api-Key"
        private const val DEVICE_ID_HEADER = "X-Chronicle-Device-Id"
        private const val AUTH_COOKIE = "chronicle_auth"
        private const val REVIEWER_ENROLLMENT_PATH = "/chronicle/v4/mobile/reviewer-enrollment"
        private val V4_ENROLLMENT_PATH =
            Regex("""^/chronicle/v4/study/([0-9a-fA-F-]{36})/participant/([^/]+)/enroll$""")
        private val V4_ENROLLMENT_PREVIEW_PATH =
            Regex("""^/chronicle/v4/study/([0-9a-fA-F-]{36})/participant/([^/]+)/enrollment-preview$""")
        private val LEGACY_V3_ENROLLMENT_PATH =
            Regex("""^/chronicle/v3/study/([0-9a-fA-F-]{36})/participant/([^/]+)/[^/]+/enroll$""")
        private val ENROLLMENT_PATHS = listOf(
            V4_ENROLLMENT_PATH,
            V4_ENROLLMENT_PREVIEW_PATH,
            LEGACY_V3_ENROLLMENT_PATH,
        )
        private val PUBLIC_SETTINGS_PATHS = listOf(
            Regex("""^/chronicle/v3/study/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/settings/sensors$"""),
            Regex("""^/chronicle/v3/study/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/settings/type/(?:AndroidSensor|Sensor|DataCollection|Encryption)$"""),
        )
        private const val CONTENT_ENCODING = "Content-Encoding"
        private const val IDENTITY_ENCODING = "identity"
        private const val DEFLATE_ENCODING = "deflate"
        private const val DECODE_BUFFER_BYTES = 8 * 1024
    }

    // Hazelcast map for storing used nonces (prevents replay attacks)
    private val nonceCache: IMap<String, Long> = hazelcastInstance.getMap(NONCE_CACHE_MAP_NAME)

    // reason: security HMAC/replay filter — sequential guard-clause validation with early returns
    // is the clearest, safest control flow; restructuring risks weakening signature/replay enforcement
    @Suppress("LongMethod", "ReturnCount")
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        // Only apply to mobile API endpoints
        if (!isMobileApiRequest(request)) {
            filterChain.doFilter(request, response)
            return
        }

        if (request.requestURI == REVIEWER_ENROLLMENT_PATH) {
            authenticateReviewerRequest(request, response, filterChain)
            return
        }

        // These exact read-only settings routes are intentionally anonymous in
        // ChronicleServerSecurityPod and contain no participant data or secrets.
        // Public app distributions therefore do not need an extractable global
        // HMAC credential just to obtain their study's collection configuration.
        if (isPublicSettingsRequest(request)) {
            filterChain.doFilter(request, response)
            return
        }

        if (isInternalWebRequest(request)) {
            filterChain.doFilter(request, response)
            return
        }

        val ipRef = LogSanitizer.stableFingerprint(ClientIpResolver.resolve(request), prefix = "ip")
        val safePath = LogSanitizer.sanitizeRequestPath(request.requestURI)

        // Reject malformed requests before reading or buffering their bodies.
        val signature = request.getHeader(HEADER_SIGNATURE)
        val timestampStr = request.getHeader(HEADER_TIMESTAMP)
        val nonce = request.getHeader(HEADER_NONCE)

        val enrollmentTarget = enrollmentTarget(request)
        val enrollmentCode = request.getHeader(HEADER_ENROLLMENT_CODE)
        if (!enrollmentCode.isNullOrBlank()) {
            if (enrollmentTarget == null || participantFormAccessService == null) {
                sendError(response, HttpStatus.UNAUTHORIZED, "Invalid enrollment credential")
                return
            }
            val securityContext = SecurityContextHolder.getContext()
            val previousAuthentication = securityContext.authentication
            val bootstrapAuthentication = MobileEnrollmentAuthenticationToken(enrollmentTarget.studyId)
            // The access-code table is protected by study RLS. Install only the narrowly
            // scoped bootstrap identity before borrowing the connection. V4 validates
            // without consuming so the controller can first bind final enrollment to the
            // accepted manifest digest; legacy v3 keeps its historical consume-at-filter behavior.
            securityContext.authentication = bootstrapAuthentication
            var accepted = false
            try {
                accepted = if (isV4EnrollmentPost(request)) {
                    participantFormAccessService.resolveEnrollmentAccessCodeForRequest(
                        enrollmentCode,
                        enrollmentTarget.studyId,
                        enrollmentTarget.participantId,
                    ) != null
                } else if (isV4EnrollmentPreview(request)) {
                    participantFormAccessService.resolveEnrollmentAccessCode(
                        enrollmentCode,
                        enrollmentTarget.studyId,
                        enrollmentTarget.participantId,
                    ) != null
                } else {
                    participantFormAccessService.consumeEnrollmentAccessCode(
                        enrollmentCode,
                        enrollmentTarget.studyId,
                        enrollmentTarget.participantId,
                    )
                }
            } finally {
                if (!accepted) {
                    securityContext.authentication = previousAuthentication
                }
            }
            if (!accepted) {
                sendError(response, HttpStatus.UNAUTHORIZED, "Invalid enrollment credential")
                return
            }
            val cachedRequest = CachedBodyHttpServletRequest(request)
            continueWithDecodedBody(cachedRequest, response, filterChain)
            return
        }
        if (V4_ENROLLMENT_PREVIEW_PATH.matches(request.requestURI)) {
            sendError(response, HttpStatus.UNAUTHORIZED, "Invalid enrollment credential")
            return
        }

        // The downstream API-key filter authenticates and path-binds this request.
        // A distributed client must not also need an extractable global HMAC key.
        if (!request.getHeader(API_KEY_HEADER).isNullOrBlank()) {
            val cachedRequest = CachedBodyHttpServletRequest(request)
            continueWithDecodedBody(cachedRequest, response, filterChain)
            return
        }

        if (!signatureVerificationEnabled) {
            val cachedRequest = CachedBodyHttpServletRequest(request)
            continueWithDecodedBody(cachedRequest, response, filterChain)
            return
        }

        // Check if signature headers are present
        val hasSignatureHeaders = !signature.isNullOrBlank() &&
                !timestampStr.isNullOrBlank() &&
                !nonce.isNullOrBlank()

        if (!hasSignatureHeaders) {
            if (signingRequired) {
                log.warn(
                    "Missing signature headers for mobile API request from ipRef: {}, path: {}",
                    ipRef,
                    safePath
                )
                sendError(
                    response,
                    HttpStatus.UNAUTHORIZED,
                    "Missing required signature headers"
                )
                return
            } else {
                // Backward compatibility mode - allow unsigned requests but log warning
                log.warn(
                    "Unsigned mobile API request from ipRef: {}, path: {} - signing not enforced",
                    ipRef,
                    safePath
                )
                val cachedRequest = CachedBodyHttpServletRequest(request)
                continueWithDecodedBody(cachedRequest, response, filterChain)
                return
            }
        }

        // Parse timestamp
        val timestamp = try {
            timestampStr.toLong()
        } catch (e: NumberFormatException) {
            log.warn(
                "Invalid timestamp format from ipRef: {}, path: {}",
                ipRef,
                safePath
            )
            sendError(response, HttpStatus.BAD_REQUEST, "Invalid timestamp format")
            return
        }

        // Validate timestamp (replay prevention - time window check)
        val timestampValidation = validateTimestamp(timestamp)
        if (!timestampValidation.isValid) {
            log.warn(
                "Timestamp validation failed from ipRef: {}, path: {}, reason: {}",
                ipRef,
                safePath,
                timestampValidation.reason
            )
            val status = if (timestampValidation.reason == "Request timestamp is out of range") {
                HttpStatus.BAD_REQUEST
            } else {
                HttpStatus.UNAUTHORIZED
            }
            sendError(response, status, timestampValidation.reason)
            return
        }

        // Validate nonce format
        if (!isValidUuid(nonce)) {
            log.warn(
                "Invalid nonce format from ipRef: {}, path: {}",
                ipRef,
                safePath
            )
            sendError(response, HttpStatus.BAD_REQUEST, "Invalid nonce format")
            return
        }

        // Reject known replays before buffering the request body. Do not reserve
        // a new nonce until the HMAC is valid: otherwise unauthenticated traffic
        // can grow the cache and preempt a legitimate request's nonce.
        if (nonceCache.containsKey(nonce)) {
            log.warn(
                "Replay attack detected - duplicate nonce from ipRef: {}, path: {}",
                ipRef,
                safePath
            )
            sendError(response, HttpStatus.UNAUTHORIZED, "Request replay detected")
            return
        }

        // The request-size filter runs at a higher precedence and bounds this
        // allocation for both Content-Length and chunked requests.
        val cachedRequest = CachedBodyHttpServletRequest(request)

        // Verify against the current key and, during an explicitly configured rollout,
        // the bounded previous-key overlap. Deliberately evaluate every candidate rather
        // than short-circuiting on the first match so response timing does not reveal
        // whether a device is still using the previous key.
        val validSignature = signingSecrets.fold(false) { matched, candidateSecret ->
            val expectedSignature = computeSignature(
                method = cachedRequest.method,
                path = cachedRequest.requestURI,
                timestamp = timestampStr,
                nonce = nonce,
                body = cachedRequest.getCachedBody(),
                secret = candidateSecret,
            )
            constantTimeEquals(signature, expectedSignature) or matched
        }

        if (!validSignature) {
            log.warn(
                "Invalid signature from ipRef: {}, path: {}, method: {}",
                ipRef,
                safePath,
                cachedRequest.method
            )
            sendError(response, HttpStatus.UNAUTHORIZED, "Invalid request signature")
            return
        }

        // Atomically reserve the nonce only after authenticating the request.
        // A concurrent request with the same valid nonce loses this race.
        if (!checkAndStoreNonce(nonce)) {
            log.warn(
                "Replay attack detected - duplicate nonce from ipRef: {}, path: {}",
                ipRef,
                safePath
            )
            sendError(response, HttpStatus.UNAUTHORIZED, "Request replay detected")
            return
        }

        log.debug(
            "Request signature verified successfully for ipRef: {}, path: {}",
            ipRef,
            safePath
        )

        // Enrollment is the credential-bootstrap boundary: no per-device API
        // key exists yet. Convert the verified HMAC into a study-scoped Spring
        // authentication so the downstream RLS filter drops the connection to
        // chronicle_app with access to exactly the study in this signed path.
        authenticateEnrollmentRequest(cachedRequest)

        // Verify the exact transmitted bytes before exposing the decoded JSON
        // body to Spring's message converters.
        continueWithDecodedBody(cachedRequest, response, filterChain)
    }

    private fun authenticateEnrollmentRequest(request: HttpServletRequest) {
        val studyId = enrollmentTarget(request)?.studyId ?: return

        SecurityContextHolder.getContext().authentication = MobileApiHmacAuthenticationToken(studyId)
    }

    private fun authenticateReviewerRequest(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!request.method.equals("POST", ignoreCase = true)) {
            auditReviewerFailure(request, HttpStatus.NOT_FOUND, "wrong_method")
            sendError(response, HttpStatus.NOT_FOUND, "Not found")
            return
        }
        val configuredStudyId = reviewerStudyId
        if (reviewerEnrollmentSecret.isBlank() || configuredStudyId == null) {
            auditReviewerFailure(request, HttpStatus.NOT_FOUND, "disabled")
            sendError(response, HttpStatus.NOT_FOUND, "Not found")
            return
        }
        val providedSecret = request.getHeader(HEADER_REVIEWER_SECRET)
        if (providedSecret.isNullOrBlank() || !constantTimeEquals(providedSecret, reviewerEnrollmentSecret)) {
            auditReviewerFailure(request, HttpStatus.UNAUTHORIZED, "invalid_credential", configuredStudyId)
            sendError(response, HttpStatus.UNAUTHORIZED, "Invalid reviewer credential")
            return
        }

        SecurityContextHolder.getContext().authentication = MobileReviewerAuthenticationToken(configuredStudyId)
        filterChain.doFilter(request, response)
    }

    private fun auditReviewerFailure(
        request: HttpServletRequest,
        status: HttpStatus,
        outcome: String,
        studyId: UUID? = null,
    ) {
        val service = auditService ?: return
        val event = AuditLogEntryBuilder()
            .ipAddress(ClientIpResolver.resolve(request))
            .userAgent(request.getHeader("User-Agent"))
            .requestPath(LogSanitizer.sanitizeRequestPath(request.requestURI))
            .requestMethod(request.method)
            .action(AuditAction.UNAUTHORIZED_ACCESS)
            .resourceType("PlayReviewerEnrollmentBootstrap")
            .studyId(studyId)
            .success(false)
            .responseCode(status.value())
            .additionalData(mapOf("outcome" to outcome))
            .build()
        service.log(event)
    }

    private fun enrollmentTarget(request: HttpServletRequest): EnrollmentTarget? {
        val methodSpecificPaths = when {
            request.method.equals("GET", ignoreCase = true) -> listOf(V4_ENROLLMENT_PREVIEW_PATH)
            request.method.equals("POST", ignoreCase = true) -> listOf(V4_ENROLLMENT_PATH, LEGACY_V3_ENROLLMENT_PATH)
            else -> emptyList()
        }
        return methodSpecificPaths.asSequence()
            .mapNotNull { pattern -> pattern.matchEntire(request.requestURI) }
            .mapNotNull { match ->
                val studyId = runCatching { UUID.fromString(match.groupValues[1]) }.getOrNull()
                    ?: return@mapNotNull null
                EnrollmentTarget(
                    studyId,
                    URLDecoder.decode(match.groupValues[2], StandardCharsets.UTF_8),
                )
            }
            .firstOrNull()
    }

    private fun isV4EnrollmentPost(request: HttpServletRequest): Boolean =
        request.method.equals("POST", ignoreCase = true) && V4_ENROLLMENT_PATH.matches(request.requestURI)

    private fun isV4EnrollmentPreview(request: HttpServletRequest): Boolean =
        request.method.equals("GET", ignoreCase = true) && V4_ENROLLMENT_PREVIEW_PATH.matches(request.requestURI)

    private data class EnrollmentTarget(val studyId: UUID, val participantId: String)

    private fun continueWithDecodedBody(
        request: CachedBodyHttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val contentEncoding = request.getHeader(CONTENT_ENCODING)?.trim()
        if (contentEncoding.isNullOrEmpty() || contentEncoding.equals(IDENTITY_ENCODING, ignoreCase = true)) {
            filterChain.doFilter(request, response)
            return
        }

        if (!contentEncoding.equals(DEFLATE_ENCODING, ignoreCase = true)) {
            sendError(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Content-Encoding")
            return
        }

        val decodedBody = try {
            inflate(request.getCachedBody())
        } catch (_: DecodedBodyTooLargeException) {
            sendError(response, HttpStatus.PAYLOAD_TOO_LARGE, "Decoded request body too large")
            return
        } catch (_: IOException) {
            sendError(response, HttpStatus.BAD_REQUEST, "Invalid deflate request body")
            return
        }

        filterChain.doFilter(DecodedBodyHttpServletRequest(request, decodedBody), response)
    }

    private fun inflate(encodedBody: ByteArray): ByteArray {
        if (encodedBody.isEmpty()) {
            throw IOException("Empty deflate stream")
        }

        InflaterInputStream(ByteArrayInputStream(encodedBody)).use { input ->
            val output = ByteArrayOutputStream(minOf(maxDecodedBodyBytes, DECODE_BUFFER_BYTES))
            val buffer = ByteArray(DECODE_BUFFER_BYTES)
            var decodedByteCount = 0

            while (true) {
                val read = input.read(buffer)
                if (read == -1) {
                    break
                }
                if (read == 0) {
                    continue
                }
                if (read > maxDecodedBodyBytes - decodedByteCount) {
                    throw DecodedBodyTooLargeException()
                }
                output.write(buffer, 0, read)
                decodedByteCount += read
            }

            return output.toByteArray()
        }
    }

    /**
     * Checks if the request is for a mobile API endpoint.
     */
    private fun isMobileApiRequest(request: HttpServletRequest): Boolean {
        return MOBILE_API_PREFIXES.any { request.requestURI.startsWith(it) }
    }

    private fun isPublicSettingsRequest(request: HttpServletRequest): Boolean {
        return request.method.equals("GET", ignoreCase = true) &&
                PUBLIC_SETTINGS_PATHS.any { it.matches(request.requestURI) }
    }

    /**
     * Researcher-console requests are routed through the stable `/chronicle/api/web`
     * boundary and stamped by the reverse proxy before it rewrites them to v3.
     *
     * The marker alone is never trusted: direct mobile routes scrub it, enrollment
     * can never bypass signing, mobile credentials force signing, and the request
     * must carry a browser bearer/cookie credential that Spring validates later.
     *
     * The marker must carry an explicitly configured proxy secret verbatim under
     * constant-time comparison. A blank configuration disables the bypass; there
     * is no guessable literal fallback (CWE-290).
     */
    private fun isInternalWebRequest(request: HttpServletRequest): Boolean {
        val marker = request.getHeader(INTERNAL_WEB_HEADER)
        if (marker.isNullOrBlank() || !isTrustedInternalWebMarker(marker)) {
            return false
        }
        if (ENROLLMENT_PATHS.any { it.matches(request.requestURI) }) return false
        if (
            !request.getHeader(API_KEY_HEADER).isNullOrBlank() ||
            !request.getHeader(DEVICE_ID_HEADER).isNullOrBlank() ||
            !request.getHeader(HEADER_SIGNATURE).isNullOrBlank()
        ) {
            return false
        }
        val bearerCredential = request.getHeader("Authorization")
            ?.startsWith("Bearer ", ignoreCase = true) == true
        val cookieCredential = request.cookies?.any { cookie ->
            cookie.name == AUTH_COOKIE && cookie.value.isNotBlank()
        } == true
        return bearerCredential || cookieCredential
    }

    /** Validates the internal-web marker against an explicitly configured proxy secret. */
    private fun isTrustedInternalWebMarker(marker: String): Boolean {
        return internalWebSecret.isNotBlank() && constantTimeEquals(marker, internalWebSecret)
    }

    /**
     * Validates that the timestamp is within the acceptable time window.
     * Allows for clock skew between client and server.
     *
     * @param timestamp Unix epoch timestamp in seconds
     * @return ValidationResult indicating if timestamp is valid
     */
    private fun validateTimestamp(timestamp: Long): ValidationResult {
        val now = Instant.now()
        val requestTime = try {
            Instant.ofEpochSecond(timestamp)
        } catch (_: RuntimeException) {
            return ValidationResult(isValid = false, reason = "Request timestamp is out of range")
        }

        // Check if timestamp is in the future (beyond clock skew)
        val maxFutureTime = now.plusSeconds(clockSkewSeconds)
        if (requestTime.isAfter(maxFutureTime)) {
            return ValidationResult(
                isValid = false,
                reason = "Request timestamp is in the future"
            )
        }

        // Check if timestamp is too old
        val maxAge = Duration.ofMinutes(maxRequestAgeMinutes).plusSeconds(clockSkewSeconds)
        val oldestAllowed = now.minus(maxAge)
        if (requestTime.isBefore(oldestAllowed)) {
            return ValidationResult(
                isValid = false,
                reason = "Request timestamp has expired"
            )
        }

        return ValidationResult(isValid = true, reason = "")
    }

    /**
     * Checks if a nonce has been used before and stores it if not.
     * Uses Hazelcast distributed cache for cluster-wide nonce tracking.
     *
     * @param nonce The nonce to check and store
     * @return true if nonce is new (not a replay), false if duplicate
     */
    private fun checkAndStoreNonce(nonce: String): Boolean {
        val existingTimestamp = nonceCache.putIfAbsent(
            nonce,
            System.currentTimeMillis(),
            nonceTtlMinutes,
            TimeUnit.MINUTES
        )

        // If putIfAbsent returns null, the nonce was not present (this is a new request)
        // If it returns a value, the nonce already existed (this is a replay)
        return existingTimestamp == null
    }

    /**
     * Computes the HMAC-SHA256 signature for a request.
     *
     * Signing string format: METHOD|PATH|TIMESTAMP|NONCE|SHA256(BODY)
     *
     * @param method HTTP method (GET, POST, etc.)
     * @param path Request URI path
     * @param timestamp Unix timestamp string
     * @param nonce Request nonce
     * @param body Request body bytes
     * @return Base64-encoded HMAC signature
     */
    private fun computeSignature(
        method: String,
        path: String,
        timestamp: String,
        nonce: String,
        body: ByteArray,
        secret: String,
    ): String {
        // Compute SHA-256 hash of the body
        val bodyHash = computeBodyHash(body)

        // Build the signing string
        val signingString = buildString {
            append(method.uppercase())
            append(DELIMITER)
            append(path)
            append(DELIMITER)
            append(timestamp)
            append(DELIMITER)
            append(nonce)
            append(DELIMITER)
            append(bodyHash)
        }

        // Compute HMAC-SHA256
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val secretKey = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM)
        mac.init(secretKey)

        val hmacBytes = mac.doFinal(signingString.toByteArray(Charsets.UTF_8))

        // Return Base64-encoded signature
        return Base64.getEncoder().encodeToString(hmacBytes)
    }

    /**
     * Computes SHA-256 hash of the request body.
     *
     * @param body Request body bytes
     * @return Hex-encoded SHA-256 hash
     */
    private fun computeBodyHash(body: ByteArray): String {
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        val hashBytes = digest.digest(body)

        // Convert to hex string
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Performs constant-time string comparison to prevent timing attacks.
     *
     * Uses MessageDigest.isEqual() which is designed to prevent
     * timing attacks by taking the same amount of time regardless
     * of where the strings differ.
     *
     * @param provided The signature provided by the client
     * @param expected The signature computed by the server
     * @return true if signatures match, false otherwise
     */
    private fun constantTimeEquals(provided: String, expected: String): Boolean {
        val providedBytes = provided.toByteArray(Charsets.UTF_8)
        val expectedBytes = expected.toByteArray(Charsets.UTF_8)

        // MessageDigest.isEqual performs constant-time comparison
        return MessageDigest.isEqual(providedBytes, expectedBytes)
    }

    /**
     * Validates that a string is a valid UUID format.
     */
    private fun isValidUuid(value: String): Boolean {
        return try {
            UUID.fromString(value)
            true
        } catch (e: IllegalArgumentException) {
            log.debug("Rejecting malformed nonce UUID: {}", e.message)
            false
        }
    }

    /**
     * Sends an error response with JSON body.
     */
    private fun sendError(response: HttpServletResponse, status: HttpStatus, message: String) {
        response.status = status.value()
        response.contentType = "application/json"
        response.writer.write("""{"error": "$message", "status": ${status.value()}}""")
        response.writer.flush()
    }

    /**
     * Result of timestamp validation.
     */
    private data class ValidationResult(
        val isValid: Boolean,
        val reason: String
    )

    private class DecodedBodyTooLargeException : IOException()
}
