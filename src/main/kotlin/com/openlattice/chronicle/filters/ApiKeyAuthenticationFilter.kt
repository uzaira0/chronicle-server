package com.openlattice.chronicle.filters

import com.openlattice.chronicle.generated.ChronicleHeaders
import com.openlattice.chronicle.generated.EnrollmentAttemptContracts
import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.apikey.ApiKeyInfo
import com.openlattice.chronicle.authorization.ApiKeyStudyAuthority
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.services.apikeys.MobileWithdrawalRequestIds
import com.openlattice.chronicle.services.participantaccess.BoundEnrollmentAttempt
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.util.API_KEY_HEADER
import com.openlattice.chronicle.util.RandomDeviceIdUtils
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.util.parsedPath
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.server.PathContainer
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.pattern.PathPatternParser
import java.util.UUID

/**
 * Spring Security filter that authenticates requests using API keys.
 *
 * API keys are passed in the X-Api-Key header. The filter:
 * 1. Checks for the X-Api-Key header
 * 2. Validates the key against the database
 * 3. Enforces scope (READ_ONLY, WRITE, ADMIN): per route for researcher keys, by HTTP method for device keys
 * 4. Sets the SecurityContext authentication if valid
 *
 * Registers API key auth in the filter chain
 * Enforces API key scopes
 */
public open class ApiKeyAuthenticationFilter(
    private val apiKeyService: ApiKeyService,
    private val authorizationManager: AuthorizationManager,
    private val creatorPrincipals: (String) -> Set<Principal> =
        Principals::getStoredUserPrincipals,
) : OncePerRequestFilter() {

    internal companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter::class.java)
        private const val RANDOM_DEVICE_ID_HEADER = ChronicleHeaders.RANDOM_DEVICE_ID_HEADER
        private const val AUTHORIZATION_HEADER = "Authorization"
        // The cookies ChronicleCookieOrBearerTokenResolver reads a person's token from.
        private val PERSON_CREDENTIAL_COOKIES = setOf("chronicle_auth", "authorization")
        public const val WITHDRAWAL_REQUEST_ID_HEADER: String = ChronicleHeaders.WITHDRAWAL_REQUEST_ID_HEADER
        // Cap on request-derived identifiers (studyId/participantId) when logged, to bound
        // log-entry size after CR/LF sanitization (defense against log injection / log flooding).
        private const val MAX_LOGGED_ID_LENGTH = 100
        // Mobile route paths embed studyId and participantId after these segments.
        // When a mobile-bound key is presented, we verify the path matches the key's binding.
        private val MOBILE_PATHS = listOf(StudyApi.BASE, StudyApi.V4_BASE).map { base ->
            PathPatternParser.defaultInstance.parse(base + StudyApi.STUDY_ID_PATH + StudyApi.PARTICIPANT_PATH +
                StudyApi.PARTICIPANT_ID_PATH + "/{*remaining}")
        }
        public const val CURRENT_ENROLLMENT_PATH: String = "/chronicle/v4/mobile/enrollments/current"
        private val CURRENT_ENROLLMENT = PathPatternParser.defaultInstance.parse(CURRENT_ENROLLMENT_PATH)
        // With the withdrawal route, the only route a key deleted by erasure may still reach: it answers ERASED.
        private val PARTICIPATION_STATUS_PATH = PathPatternParser.defaultInstance.parse(
            StudyApi.V4_BASE + StudyApi.STUDY_ID_PATH + StudyApi.PARTICIPANT_PATH +
                StudyApi.PARTICIPANT_ID_PATH + StudyApi.STATUS_PATH,
        )

        /** The API key a request presents, or null when it presents none; the pre-authentication limiter reads it too. */
        internal fun presentedApiKey(request: HttpServletRequest): String? =
            request.getHeader(API_KEY_HEADER)?.takeIf { it.isNotBlank() }
    }

    private fun carriesPersonCredential(request: HttpServletRequest): Boolean =
        !request.getHeader(AUTHORIZATION_HEADER).isNullOrBlank() ||
            request.cookies?.any { it.name in PERSON_CREDENTIAL_COOKIES } == true

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        // Only process requests that have an API key header
        return presentedApiKey(request) == null
    }

    // reason: pending attempts, erased keys and ordinary keys authenticate through sequential fail-closed guards
    @Suppress("LongMethod", "ReturnCount")
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val rawKey = checkNotNull(presentedApiKey(request)) { "shouldNotFilter admits only requests presenting a key" }

        // A request authenticates as one principal. The bearer-token filter runs after this one and would
        // replace the key's identity with the person's, so a key sent with a bearer token or an auth cookie
        // is refused before any handler.
        if (carriesPersonCredential(request)) {
            ChronicleMetrics.apiKeyUsageTotal.labels("unknown", "mixed_credentials").inc()
            response.sendError(HttpStatus.BAD_REQUEST.value(), Messages.get("error.apiKey.mixedCredentials", request))
            return
        }

        val path = request.parsedPath()
        val currentEnrollmentRequest = isCurrentEnrollmentRequest(request, path)
        val statusRequest = isStatusRequest(request, path)
        val requestId = if (currentEnrollmentRequest) {
            MobileWithdrawalRequestIds.parse(request.getHeader(WITHDRAWAL_REQUEST_ID_HEADER)) ?: run {
                response.sendError(HttpStatus.UNAUTHORIZED.value(), Messages.get("error.enrollment.apiKeyInvalid", request))
                return
            }
        } else null
        // A phone cancelling its enrollment attempt names the attempt and its proposed key; the binding they name
        // authenticates the cancel whether or not the key installed, so a cancel never reaches withdrawal.
        val pendingAttempt = requestId?.let {
            apiKeyService.authenticatePendingEnrollmentAttempt(rawKey, it, request.getHeader(RANDOM_DEVICE_ID_HEADER))
        }
        if (pendingAttempt != null) {
            ChronicleMetrics.apiKeyUsageTotal.labels("pending_attempt", "success").inc()
            SecurityContextHolder.getContext().authentication = ApiKeyAuthenticationToken.pendingAttempt(
                pendingAttempt, checkNotNull(ApiKeyService.proposedMobileApiKeyHash(rawKey)))
            filterChain.doFilter(request, response)
            return
        }
        val keyInfo = if (requestId != null) {
            apiKeyService.authenticateWithdrawalApiKey(rawKey, requestId)
        } else {
            apiKeyService.authenticateApiKey(rawKey)
        }
        // A device key that participant or study erasure deleted keeps only a hash tombstone. It reaches the
        // status read (ERASED) and the withdrawal acknowledgment; a merely revoked key stays 401.
        if (isErasedKeyRequest(rawKey, keyInfo, statusRequest, currentEnrollmentRequest)) {
            ChronicleMetrics.apiKeyUsageTotal.labels("erased", "success").inc()
            SecurityContextHolder.getContext().authentication = ApiKeyAuthenticationToken.erased(
                LogSanitizer.stableFingerprint(rawKey, "erased-key"))
            filterChain.doFilter(request, response)
            return
        }
        if (keyInfo == null) {
            log.warn("Invalid API key")
            ChronicleMetrics.apiKeyUsageTotal.labels("unknown", "invalid").inc()
            // The key lookup is deterministic, so a refused withdrawal can never succeed with this key: 410 sends
            // the phone to study support, while 401 (signature or clock refusals) stays retryable. The key of a
            // cancelled enrollment attempt is answered as cancelled on every route it reaches, Leave study
            // included (a cancelled attempt has nothing to withdraw), coded so the phone ends that enrollment.
            if (apiKeyService.isCancelledEnrollmentKey(rawKey)) {
                writeFilterError(response, HttpStatus.GONE.value(), Messages.get("error.enrollment.cancelled", request),
                    EnrollmentAttemptContracts.ENROLLMENT_CANCELLED_CODE)
                return
            }
            val status = if (currentEnrollmentRequest) HttpStatus.GONE else HttpStatus.UNAUTHORIZED
            response.sendError(status.value(), Messages.get("error.enrollment.apiKeyInvalid", request))
            return
        }

        // Track successful API key usage for anomaly detection
        ChronicleMetrics.apiKeyUsageTotal.labels(keyInfo.prefix, "success").inc()

        val scope = resolvedScope(request, response, keyInfo) ?: return
        if (!hasMobileBinding(request, response, keyInfo, currentEnrollmentRequest, path)) return

        // Set authentication in SecurityContext
        val authorities = listOf(
            SimpleGrantedAuthority("ROLE_API_KEY"),
            SimpleGrantedAuthority("SCOPE_${scope.name}")
        )
        val authentication = ApiKeyAuthenticationToken(
            principal = "apikey:${keyInfo.keyId}",
            keyId = keyInfo.keyId,
            studyId = keyInfo.studyId,
            participantId = keyInfo.participantId,
            randomDeviceId = keyInfo.randomDeviceId,
            scope = scope,
            authorities = authorities,
            creatorUserId = keyInfo.createdBy,
        )
        SecurityContextHolder.getContext().authentication = authentication

        filterChain.doFilter(request, response)
    }

    private fun isErasedKeyRequest(
        rawKey: String,
        keyInfo: ApiKeyInfo?,
        statusRequest: Boolean,
        currentEnrollmentRequest: Boolean,
    ): Boolean {
        val erasedKeyRequest = statusRequest || currentEnrollmentRequest
        return keyInfo == null && erasedKeyRequest && apiKeyService.isErasedDeviceKey(rawKey)
    }

    private fun isCurrentEnrollmentRequest(request: HttpServletRequest, path: PathContainer?): Boolean =
        request.method.equals(HttpMethod.DELETE.name(), ignoreCase = true) && path?.let(CURRENT_ENROLLMENT::matches) == true

    private fun isStatusRequest(request: HttpServletRequest, path: PathContainer?): Boolean =
        request.method.equals(HttpMethod.GET.name(), ignoreCase = true) &&
            path?.let(PARTICIPATION_STATUS_PATH::matchAndExtract)?.uriVariables?.get(StudyApi.STUDY_ID)?.let {
                runCatching { UUID.fromString(it.trim()) }.isSuccess
            } == true

    private fun resolvedScope(
        request: HttpServletRequest,
        response: HttpServletResponse,
        keyInfo: ApiKeyInfo,
    ): ApiKeyScope? {
        // A researcher key reaches only the study-scoped routes in ResearcherApiKeyRoutes, on its own
        // study, with the scope each route needs; a device key keeps the HTTP-method rule below.
        var scope = keyInfo.scope
        val requiredScope = if (keyInfo.participantId == null) {
            val route = ResearcherApiKeyRoutes.match(request.method, request.requestURI)
            if (route == null || route.studyId != keyInfo.studyId) {
                log.warn(
                    "Researcher API key {} refused on {} {}",
                    keyInfo.keyId,
                    request.method,
                    LogSanitizer.sanitizeRequestPath(request.requestURI)
                )
                ChronicleMetrics.apiKeyUsageTotal.labels(keyInfo.prefix, "path_mismatch").inc()
                val message = if (route == null) "error.enrollment.apiKeyEndpoint" else "error.enrollment.apiKeyStudyScope"
                response.sendError(HttpStatus.FORBIDDEN.value(), Messages.get(message, request))
                return null
            }
            // A researcher key acts with its scope cut down to what its creator holds on the study right now.
            val ceiling = keyInfo.createdBy?.let {
                ApiKeyStudyAuthority.ceiling(authorizationManager, keyInfo.studyId, creatorPrincipals(it))
            }
            if (ceiling == null) {
                log.warn("Researcher API key {} refused: its creator no longer has access to the study", keyInfo.keyId)
                response.sendError(HttpStatus.FORBIDDEN.value(), Messages.get("error.enrollment.apiKeyCreatorAccess", request))
                return null
            }
            scope = minOf(scope, ceiling)
            route.scope
        } else {
            getRequiredScope(request.method)
        }
        if (!ApiKeyStudyAuthority.covers(scope, requiredScope)) {
            log.warn(
                "API key scope {} insufficient for {} request on studyRef {}",
                scope,
                request.method,
                LogSanitizer.stableFingerprint(keyInfo.studyId.toString(), prefix = "study")
            )
            response.sendError(HttpStatus.FORBIDDEN.value(), Messages.get("error.enrollment.apiKeyScope", request))
            return null
        }
        return scope
    }

    private fun hasMobileBinding(
        request: HttpServletRequest,
        response: HttpServletResponse,
        keyInfo: ApiKeyInfo,
        currentEnrollmentRequest: Boolean,
        path: PathContainer?,
    ): Boolean {
        // Path-bound enforcement for mobile keys: a key issued for
        // (studyId, participantId, randomDeviceId) can only be used on paths matching
        // its studyId and participantId. A mismatch is a strong indicator of
        // credential theft or replay; emit a metric so anomaly detection can react.
        val boundParticipantId = keyInfo.participantId ?: return true
        val match = path?.let { candidate -> MOBILE_PATHS.firstNotNullOfOrNull { it.matchAndExtract(candidate) } }
        if (!currentEnrollmentRequest && match == null) {
            log.warn(
                "Mobile API key {} used on non-mobile path {}",
                keyInfo.keyId,
                LogSanitizer.sanitizeRequestPath(request.requestURI)
            )
            ChronicleMetrics.apiKeyUsageTotal.labels(keyInfo.prefix, "path_mismatch").inc()
            response.sendError(HttpStatus.FORBIDDEN.value(), Messages.get("error.enrollment.apiKeyEndpoint", request))
            return false
        }
        val pathStudyId = match?.uriVariables?.get(StudyApi.STUDY_ID)
            ?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
        val pathParticipantId = match?.uriVariables?.get(StudyApi.PARTICIPANT_ID)
        if (!currentEnrollmentRequest &&
            (pathStudyId != keyInfo.studyId || pathParticipantId != boundParticipantId)
        ) {
            log.warn(
                "Mobile API key {} (studyRef={}, participantRef={}) does not match path " +
                    "(studyRef={}, participantRef={})",
                keyInfo.keyId,
                LogSanitizer.stableFingerprint(keyInfo.studyId.toString(), prefix = "study"),
                LogSanitizer.stableFingerprint(boundParticipantId, prefix = "participant"),
                LogSanitizer.stableFingerprint(
                    LogSanitizer.sanitize(pathStudyId?.toString(), MAX_LOGGED_ID_LENGTH),
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
            return false
        }

        return hasDeviceBinding(request, response, keyInfo, boundParticipantId)
    }

    private fun hasDeviceBinding(
        request: HttpServletRequest,
        response: HttpServletResponse,
        keyInfo: ApiKeyInfo,
        boundParticipantId: String,
    ): Boolean {
        val boundRandomDeviceId = keyInfo.randomDeviceId
        if (boundRandomDeviceId != null) {
            val sourceRandomDeviceId = request.getHeader(RANDOM_DEVICE_ID_HEADER)
            val requestRandomDeviceId = sourceRandomDeviceId
                ?.takeIf { it.isNotBlank() }
                ?.let { RandomDeviceIdUtils.deriveRandomDeviceId(keyInfo.studyId, boundParticipantId, it) }
            if (requestRandomDeviceId != boundRandomDeviceId) {
                log.warn(
                    "Mobile API key {} device binding does not match request device header",
                    keyInfo.keyId
                )
                ChronicleMetrics.apiKeyUsageTotal.labels(keyInfo.prefix, "device_mismatch").inc()
                response.sendError(
                    HttpStatus.FORBIDDEN.value(),
                    Messages.get("error.enrollment.apiKeyDeviceScope", request),
                )
                return false
            }
        }
        return true
    }

    private fun getRequiredScope(method: String): ApiKeyScope {
        return when (method.uppercase()) {
            HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name() -> ApiKeyScope.READ_ONLY
            HttpMethod.POST.name(), HttpMethod.PUT.name(), HttpMethod.PATCH.name(), HttpMethod.DELETE.name() -> ApiKeyScope.WRITE
            else -> ApiKeyScope.ADMIN
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
    public val randomDeviceId: java.util.UUID?,
    public val scope: ApiKeyScope,
    authorities: List<SimpleGrantedAuthority>,
    public val creatorUserId: String? = null,
    /** Null for a live key; otherwise the one narrow thing a credential without a live key may reach. */
    public val restriction: Restriction? = null,
) : AbstractAuthenticationToken(authorities) {
    init {
        isAuthenticated = true
    }

    public sealed interface Restriction {
        /** A key deleted by erasure: the status read and the withdrawal acknowledgment only. */
        public object Erased : Restriction

        /** A bound enrollment attempt whose proposed key never installed: cancelling that attempt only. */
        public data class PendingAttempt(val proposedKeyHash: String) : Restriction
    }

    public val erased: Boolean get() = restriction == Restriction.Erased

    public val pendingAttemptKeyHash: String? get() = (restriction as? Restriction.PendingAttempt)?.proposedKeyHash

    public companion object {
        private val NO_ID = java.util.UUID(0L, 0L)

        /** The tombstone holds no identifiers, so the token binds no key, study, participant or device. */
        public fun erased(credentialReference: String = "erased"): ApiKeyAuthenticationToken = ApiKeyAuthenticationToken(
            principal = "apikey:$credentialReference",
            keyId = NO_ID,
            studyId = NO_ID,
            participantId = null,
            randomDeviceId = null,
            scope = ApiKeyScope.READ_ONLY,
            authorities = listOf(SimpleGrantedAuthority("ROLE_API_KEY")),
            restriction = Restriction.Erased,
        )

        internal fun pendingAttempt(attempt: BoundEnrollmentAttempt, proposedKeyHash: String): ApiKeyAuthenticationToken =
            ApiKeyAuthenticationToken(
                principal = "apikey:pending-attempt",
                keyId = NO_ID,
                studyId = attempt.studyId,
                participantId = attempt.participantId,
                randomDeviceId = attempt.randomDeviceId,
                scope = ApiKeyScope.READ_ONLY,
                authorities = listOf(SimpleGrantedAuthority("ROLE_API_KEY")),
                restriction = Restriction.PendingAttempt(proposedKeyHash),
            )
    }

    override fun getCredentials(): Any = ""
    override fun getPrincipal(): Any = principal
}
