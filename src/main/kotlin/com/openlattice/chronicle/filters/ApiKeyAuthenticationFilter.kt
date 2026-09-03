package com.openlattice.chronicle.filters

import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.services.apikeys.MobileWithdrawalRequestIds
import com.openlattice.chronicle.services.security.HoneyTokenService
import com.openlattice.chronicle.util.ClientIpResolver
import com.openlattice.chronicle.util.DeviceIdUtils
import com.openlattice.chronicle.util.LogSanitizer
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Spring Security filter that authenticates requests using API keys.
 *
 * API keys are passed in the X-Api-Key header. The filter:
 * 1. Checks for the X-Api-Key header
 * 2. Validates the key against the database
 * 3. Enforces scope (READ_ONLY, WRITE, ADMIN) based on HTTP method
 * 4. Sets the SecurityContext authentication if valid
 *
 * H-1: Registers API key auth in the filter chain
 * H-2: Enforces API key scopes
 */
public open class ApiKeyAuthenticationFilter(
    private val apiKeyService: ApiKeyService,
    private val honeyTokenService: HoneyTokenService? = null
) : OncePerRequestFilter() {

    internal companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter::class.java)
        private const val API_KEY_HEADER = "X-Api-Key"
        private const val DEVICE_ID_HEADER = "X-Chronicle-Device-Id"
        public const val WITHDRAWAL_REQUEST_ID_HEADER: String = "X-Chronicle-Withdrawal-Request-Id"
        // Cap on request-derived identifiers (studyId/participantId) when logged, to bound
        // log-entry size after CR/LF sanitization (defense against log injection / log flooding).
        private const val MAX_LOGGED_ID_LENGTH = 100
        // Mobile route paths embed studyId and participantId after these segments.
        // When a mobile-bound key is presented, we verify the path matches the key's binding.
        private val MOBILE_PATH_REGEX = Regex(
            """/chronicle/v[34]/study/([0-9a-fA-F-]+)/participant/([^/]+)(?:/.*)?"""
        )
        public const val CURRENT_ENROLLMENT_PATH: String = "/chronicle/v4/mobile/enrollments/current"
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        // Only process requests that have an API key header
        return request.getHeader(API_KEY_HEADER).isNullOrBlank()
    }

    // reason: security filter — sequential fail-closed guard clauses (honey-token, scope, path/device binding)
    // are clearest as in-line early returns; extracting would obscure the request-rejection control flow
    @Suppress("LongMethod", "ReturnCount")
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val rawKey = request.getHeader(API_KEY_HEADER)
        val sourceIp = ClientIpResolver.resolve(request)

        // Honey token detection: check before normal authentication.
        // Any use of a honey token means unauthorized access or credential theft.
        if (honeyTokenService != null && honeyTokenService.isProbablyHoneyToken(rawKey)) {
            honeyTokenService.checkAndAlert(rawKey, sourceIp)
            // Respond with generic 401 (do not reveal that we detected the canary)
            ChronicleMetrics.apiKeyUsageTotal.labels("honey", "rejected").inc()
            response.sendError(HttpStatus.UNAUTHORIZED.value(), Messages.get("error.enrollment.apiKeyInvalid", request))
            return
        }

        val currentEnrollmentRequest = request.method.equals(HttpMethod.DELETE.name(), ignoreCase = true) &&
            request.requestURI == CURRENT_ENROLLMENT_PATH
        val keyInfo = if (currentEnrollmentRequest) {
            val requestId = MobileWithdrawalRequestIds.parse(request.getHeader(WITHDRAWAL_REQUEST_ID_HEADER))
            if (requestId == null) {
                response.sendError(HttpStatus.UNAUTHORIZED.value(), Messages.get("error.enrollment.apiKeyInvalid", request))
                return
            }
            apiKeyService.authenticateWithdrawalApiKey(rawKey, requestId)
        } else {
            apiKeyService.authenticateApiKey(rawKey)
        }
        if (keyInfo == null) {
            log.warn(
                "Invalid API key from ipRef: {}",
                LogSanitizer.stableFingerprint(sourceIp, prefix = "ip")
            )
            ChronicleMetrics.apiKeyUsageTotal.labels("unknown", "invalid").inc()
            response.sendError(HttpStatus.UNAUTHORIZED.value(), Messages.get("error.enrollment.apiKeyInvalid", request))
            return
        }

        // Track successful API key usage for anomaly detection
        ChronicleMetrics.apiKeyUsageTotal.labels(keyInfo.prefix, "success").inc()
        ChronicleMetrics.apiKeySourceIpHash.labels(keyInfo.prefix).set(sourceIp.hashCode().toDouble())

        // H-2: Enforce scope based on HTTP method
        val requiredScope = getRequiredScope(request.method)
        if (!isScopeAllowed(keyInfo.scope, requiredScope)) {
            log.warn(
                "API key scope {} insufficient for {} request on studyRef {}",
                keyInfo.scope,
                request.method,
                LogSanitizer.stableFingerprint(keyInfo.studyId.toString(), prefix = "study")
            )
            response.sendError(HttpStatus.FORBIDDEN.value(), Messages.get("error.enrollment.apiKeyScope", request))
            return
        }

        // Path-bound enforcement for mobile keys: a key issued for
        // (studyId, participantId, deviceId) can only be used on paths matching
        // its studyId and participantId. A mismatch is a strong indicator of
        // credential theft or replay; emit a metric so anomaly detection can react.
        val boundParticipantId = keyInfo.participantId
        if (boundParticipantId != null) {
            val match = MOBILE_PATH_REGEX.matchEntire(request.requestURI)
            if (!currentEnrollmentRequest && match == null) {
                log.warn(
                    "Mobile API key {} used on non-mobile path {}",
                    keyInfo.keyId,
                    LogSanitizer.sanitizeRequestPath(request.requestURI)
                )
                ChronicleMetrics.apiKeyUsageTotal.labels(keyInfo.prefix, "path_mismatch").inc()
                response.sendError(HttpStatus.FORBIDDEN.value(), Messages.get("error.enrollment.apiKeyEndpoint", request))
                return
            }
            val pathStudyId = match?.groupValues?.get(1)
            val pathParticipantId = match?.groupValues?.get(2)?.let {
                URLDecoder.decode(it, StandardCharsets.UTF_8)
            }
            if (!currentEnrollmentRequest &&
                (!pathStudyId.equals(keyInfo.studyId.toString(), ignoreCase = true) || pathParticipantId != boundParticipantId)
            ) {
                log.warn(
                    "Mobile API key {} (studyRef={}, participantRef={}) does not match path " +
                        "(studyRef={}, participantRef={})",
                    keyInfo.keyId,
                    LogSanitizer.stableFingerprint(keyInfo.studyId.toString(), prefix = "study"),
                    LogSanitizer.stableFingerprint(boundParticipantId, prefix = "participant"),
                    LogSanitizer.stableFingerprint(
                        LogSanitizer.sanitize(pathStudyId, MAX_LOGGED_ID_LENGTH),
                        prefix = "study"
                    ),
                    LogSanitizer.stableFingerprint(
                        LogSanitizer.sanitize(pathParticipantId, MAX_LOGGED_ID_LENGTH),
                        prefix = "participant"
                    )
                )
                ChronicleMetrics.apiKeyUsageTotal.labels(keyInfo.prefix, "path_mismatch").inc()
                response.sendError(
                    HttpStatus.FORBIDDEN.value(),
                    Messages.get("error.enrollment.apiKeyStudyScope", request),
                )
                return
            }

            val boundDeviceId = keyInfo.deviceId
            if (boundDeviceId != null) {
                val sourceDeviceId = request.getHeader(DEVICE_ID_HEADER)
                val requestDeviceId = sourceDeviceId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { DeviceIdUtils.deriveDeviceId(keyInfo.studyId, boundParticipantId, it) }
                if (requestDeviceId != boundDeviceId) {
                    log.warn(
                        "Mobile API key {} device binding does not match request device header",
                        keyInfo.keyId
                    )
                    ChronicleMetrics.apiKeyUsageTotal.labels(keyInfo.prefix, "device_mismatch").inc()
                    response.sendError(
                        HttpStatus.FORBIDDEN.value(),
                        Messages.get("error.enrollment.apiKeyDeviceScope", request),
                    )
                    return
                }
            }
        }

        // Set authentication in SecurityContext
        val authorities = listOf(
            SimpleGrantedAuthority("ROLE_API_KEY"),
            SimpleGrantedAuthority("SCOPE_${keyInfo.scope.name}")
        )
        val authentication = ApiKeyAuthenticationToken(
            principal = "apikey:${keyInfo.keyId}",
            keyId = keyInfo.keyId,
            studyId = keyInfo.studyId,
            participantId = keyInfo.participantId,
            deviceId = keyInfo.deviceId,
            scope = keyInfo.scope,
            authorities = authorities
        )
        SecurityContextHolder.getContext().authentication = authentication

        filterChain.doFilter(request, response)
    }

    private fun getRequiredScope(method: String): ApiKeyScope {
        return when (method.uppercase()) {
            HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name() -> ApiKeyScope.READ_ONLY
            HttpMethod.POST.name(), HttpMethod.PUT.name(), HttpMethod.PATCH.name(), HttpMethod.DELETE.name() -> ApiKeyScope.WRITE
            else -> ApiKeyScope.ADMIN
        }
    }

    private fun isScopeAllowed(granted: ApiKeyScope, required: ApiKeyScope): Boolean {
        return when (granted) {
            ApiKeyScope.ADMIN -> true // ADMIN can do everything
            ApiKeyScope.WRITE -> required != ApiKeyScope.ADMIN
            ApiKeyScope.READ_ONLY -> required == ApiKeyScope.READ_ONLY
        }
    }
}

/**
 * Authentication token for API key-authenticated requests.
 */
public open class ApiKeyAuthenticationToken(
    private val principal: String,
    public val keyId: java.util.UUID,
    public val studyId: java.util.UUID,
    public val participantId: String?,
    public val deviceId: java.util.UUID?,
    public val scope: ApiKeyScope,
    authorities: List<SimpleGrantedAuthority>
) : AbstractAuthenticationToken(authorities) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any = ""
    override fun getPrincipal(): Any = principal
}
