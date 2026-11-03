package com.openlattice.chronicle.filters

import com.openlattice.chronicle.configuration.RateLimitFilter

import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.generated.ChronicleHeaders
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessScope
import com.openlattice.chronicle.storage.ParticipantProcessingBlockedException
import com.openlattice.chronicle.generated.ParticipantProcessingContracts
import com.openlattice.chronicle.storage.rls.RLSConnectionContext
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.survey.SurveyApi
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi
import com.openlattice.chronicle.util.matchingSegments
import com.openlattice.chronicle.util.parsedPath
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.util.pattern.PathPatternParser
import java.util.UUID
import java.time.LocalDate
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/** Fail-closed capability gate for the otherwise anonymous participant web-form surface. */
public class ParticipantFormAccessFilter(
    private val participantFormAccessService: ParticipantFormAccessService,
) : OncePerRequestFilter() {
    public companion object {
        public const val INVALID_SESSION_MESSAGE_KEY: String = "error.form.sessionInvalid"
        public const val SESSION_COOKIE: String = "__Host-chronicle-form"
        public const val SESSION_RECOVERY_PATH: String = "/chronicle/v3/participant-access/session"
        public const val CSRF_HEADER: String = ChronicleHeaders.PARTICIPANT_FORM_CSRF_HEADER
        public const val IDEMPOTENCY_HEADER: String = ChronicleHeaders.IDEMPOTENCY_KEY_HEADER
        public const val IDEMPOTENCY_ATTRIBUTE: String = "chronicle.participantFormIdempotencyKey"
        public const val REQUEST_SCOPE_ATTRIBUTE: String = "chronicle.participantFormScope"
        public const val SUBMISSION_DATE_ATTRIBUTE: String = "chronicle.participantFormDate"

        public fun currentScope(): ParticipantFormAccessScope? =
            RequestContextHolder.getRequestAttributes()
                ?.getAttribute(REQUEST_SCOPE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) as? ParticipantFormAccessScope

        public fun requireIdempotencyKey(): UUID = requireNotNull(
            RequestContextHolder.getRequestAttributes()
                ?.getAttribute(IDEMPOTENCY_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) as? UUID,
        ) { "Participant idempotency key was not established by the access filter" }

        public fun requireSubmissionDate(): LocalDate = requireNotNull(
            RequestContextHolder.getRequestAttributes()
                ?.getAttribute(SUBMISSION_DATE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) as? LocalDate,
        ) { "Daily submission date was not established by the access filter" }

        /**
         * Checks a session read-only on a form route or GET recovery, using this filter's cookie and CSRF rules.
         * Recovery ignores the per-tab header, matching the service's cookie-only recovery. Resource authorization
         * remains with the form filter; this pre-authentication check must not extend the session's idle clock.
         */
        public fun hasAdmittedSession(request: HttpServletRequest, service: ParticipantFormAccessService): Boolean {
            val rawSession = sessionCookie(request) ?: return false
            val recovery = request.method == HttpMethod.GET.name() &&
                request.parsedPath()?.matchingSegments() in SESSION_RECOVERY_ROUTES
            if (!recovery && !gates(request)) return false
            return service.isAdmittedSession(rawSession, if (recovery) null else request.getHeader(CSRF_HEADER), requiresCsrf(request))
        }

        private val SESSION_RECOVERY_ROUTES = listOf(SESSION_RECOVERY_PATH, SESSION_RECOVERY_PATH.removePrefix("/chronicle"))
            .map { requireNotNull(it.parsedPath()).matchingSegments() }

        private val SAFE_METHODS = setOf(HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name())

        private fun requiresCsrf(request: HttpServletRequest): Boolean = request.method !in SAFE_METHODS

        private fun sessionCookie(request: HttpServletRequest): String? =
            request.cookies?.firstOrNull { it.name == SESSION_COOKIE }?.value

        /** The participant form namespaces; an unparseable path is gated too, and fails closed there. */
        private fun gates(request: HttpServletRequest): Boolean {
            val segments = request.parsedPath()?.matchingSegments() ?: return true
            return FORM_NAMESPACES.any { prefix -> segments.take(prefix.size) == prefix }
        }

        private val FORM_NAMESPACES = listOf(SurveyApi.BASE, TimeUseDiaryApi.BASE)
            .map { requireNotNull(it.parsedPath()).matchingSegments() }
        private val SURVEY_PARTICIPANT = listOf(SurveyApi.APP_USAGE_PATH, SurveyApi.APP_USAGE_DAYS_PATH, SurveyApi.DEVICE_USAGE_PATH).map { suffix ->
            PathPatternParser.defaultInstance.parse(SurveyApi.BASE + SurveyApi.STUDY_ID_PATH +
                SurveyApi.PARTICIPANT_PATH + SurveyApi.PARTICIPANT_ID_PATH + suffix)
        }
        private val SURVEY_FREQUENCY = PathPatternParser.defaultInstance.parse(
            SurveyApi.BASE + SurveyApi.STUDY_ID_PATH + SurveyApi.APP_USAGE_FREQUENCY_PATH,
        )
        private val QUESTIONNAIRE_READ = PathPatternParser.defaultInstance.parse(
            SurveyApi.BASE + SurveyApi.STUDY_ID_PATH + SurveyApi.QUESTIONNAIRE_PATH + SurveyApi.QUESTIONNAIRE_ID_PATH,
        )
        private val QUESTIONNAIRE_SUBMIT = PathPatternParser.defaultInstance.parse(
            SurveyApi.BASE + SurveyApi.STUDY_ID_PATH + SurveyApi.PARTICIPANT_PATH + SurveyApi.PARTICIPANT_ID_PATH +
                SurveyApi.QUESTIONNAIRE_PATH + SurveyApi.QUESTIONNAIRE_ID_PATH,
        )
        private val TUD_SETTINGS = PathPatternParser.defaultInstance.parse(
            TimeUseDiaryApi.BASE + TimeUseDiaryApi.STUDY_ID_PATH + TimeUseDiaryApi.SETTINGS_PATH,
        )
        private val TUD_PARTICIPANT = PathPatternParser.defaultInstance.parse(
            TimeUseDiaryApi.BASE + TimeUseDiaryApi.STUDY_ID_PATH +
                TimeUseDiaryApi.PARTICIPANT_PATH + TimeUseDiaryApi.PARTICIPANT_ID_PATH,
        )
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !gates(request)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication?.isAuthenticated == true && authentication !is AnonymousAuthenticationToken) {
            filterChain.doFilter(request, response)
            return
        }
        val target = target(request) ?: run {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), Messages.get(INVALID_SESSION_MESSAGE_KEY, request))
            return
        }

        val requireCsrf = requiresCsrf(request)
        val scope = authorizedScope(request, response, target, requireCsrf) ?: return

        RateLimitFilter.refundAuthenticationReservation(request)
        if (requireCsrf && !prepareSubmission(request, response, target.kind, scope)) return

        ChronicleMetrics.participantFormAccessTotal.labels(target.kind.name, "accepted").inc()
        request.setAttribute(REQUEST_SCOPE_ATTRIBUTE, scope)
        RLSRequestContext.set(
            RLSConnectionContext(
                principalId = "participant-access:${scope.accessCodeId}",
                authorizedStudyIds = setOf(scope.studyId),
                isAdmin = false,
            )
        )
        try {
            filterChain.doFilter(request, response)
        } finally {
            RLSRequestContext.clear()
        }
    }

    /** The session scope that may reach [target], or null once the rejection has been written. */
    private fun authorizedScope(
        request: HttpServletRequest,
        response: HttpServletResponse,
        target: Target,
        requireCsrf: Boolean,
    ): ParticipantFormAccessScope? {
        val rawSession = sessionCookie(request)
        val scope = try {
            rawSession?.let { participantFormAccessService.resolveSession(it, request.getHeader(CSRF_HEADER), requireCsrf) }
        } catch (blocked: ParticipantProcessingBlockedException) {
            writeFilterError(response, blocked.statusCode.value(), checkNotNull(blocked.reason),
                ParticipantProcessingContracts.PARTICIPANT_PROCESSING_BLOCKED_CODE)
            return null
        }
        if (scope == null || !scope.permits(target.kind, target.studyId, target.participantId, target.resourceId)) {
            ChronicleMetrics.participantFormAccessTotal.labels(target.kind.name, "rejected").inc()
            response.sendError(HttpStatus.UNAUTHORIZED.value(), Messages.get(INVALID_SESSION_MESSAGE_KEY, request))
            return null
        }
        return scope
    }

    private fun prepareSubmission(
        request: HttpServletRequest,
        response: HttpServletResponse,
        kind: ParticipantFormKind,
        scope: ParticipantFormAccessScope,
    ): Boolean {
        val idempotencyKey = request.getHeader(IDEMPOTENCY_HEADER)?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }
        if (idempotencyKey == null) {
            response.sendError(HttpStatus.BAD_REQUEST.value(), Messages.get("error.form.idempotencyKeyRequired", request))
            return false
        }
        if (kind.requiresSubmissionDate) {
            val day = request.getHeader(ChronicleHeaders.PARTICIPANT_FORM_DATE_HEADER)?.let {
                runCatching { LocalDate.parse(it) }.getOrNull()
            }
            if (day == null || (scope.logicalDate != null && scope.logicalDate != day)) {
                response.sendError(HttpStatus.BAD_REQUEST.value(), Messages.get("error.form.submissionDateRequired", request))
                return false
            }
            request.setAttribute(SUBMISSION_DATE_ATTRIBUTE, day)
        }
        request.setAttribute(IDEMPOTENCY_ATTRIBUTE, idempotencyKey)
        return true
    }

    private fun target(request: HttpServletRequest): Target? {
        val path = request.parsedPath() ?: return null
        SURVEY_PARTICIPANT.firstNotNullOfOrNull { it.matchAndExtract(path) }?.let { match ->
            if (request.method !in setOf(HttpMethod.GET.name(), HttpMethod.POST.name())) return null
            return Target(
                kind = ParticipantFormKind.APP_USAGE,
                studyId = uuid(match.uriVariables.getValue(SurveyApi.STUDY_ID)) ?: return null,
                participantId = match.uriVariables.getValue(SurveyApi.PARTICIPANT_ID),
            )
        }
        SURVEY_FREQUENCY.matchAndExtract(path)?.let { match ->
            if (request.method != HttpMethod.GET.name()) return null
            return Target(
                ParticipantFormKind.APP_USAGE, uuid(match.uriVariables.getValue(SurveyApi.STUDY_ID)) ?: return null,
            )
        }
        QUESTIONNAIRE_READ.matchAndExtract(path)?.let { match ->
            if (request.method != HttpMethod.GET.name()) return null
            return Target(
                ParticipantFormKind.QUESTIONNAIRE,
                uuid(match.uriVariables.getValue(SurveyApi.STUDY_ID)) ?: return null,
                resourceId = uuid(match.uriVariables.getValue(SurveyApi.QUESTIONNAIRE_ID)) ?: return null,
            )
        }
        QUESTIONNAIRE_SUBMIT.matchAndExtract(path)?.let { match ->
            if (request.method != HttpMethod.POST.name()) return null
            return Target(
                ParticipantFormKind.QUESTIONNAIRE,
                uuid(match.uriVariables.getValue(SurveyApi.STUDY_ID)) ?: return null,
                participantId = match.uriVariables.getValue(SurveyApi.PARTICIPANT_ID),
                resourceId = uuid(match.uriVariables.getValue(SurveyApi.QUESTIONNAIRE_ID)) ?: return null,
            )
        }
        TUD_SETTINGS.matchAndExtract(path)?.let { match ->
            if (request.method != HttpMethod.GET.name()) return null
            return Target(
                ParticipantFormKind.TIME_USE_DIARY,
                uuid(match.uriVariables.getValue(TimeUseDiaryApi.STUDY_ID)) ?: return null,
            )
        }
        TUD_PARTICIPANT.matchAndExtract(path)?.let { match ->
            if (request.method !in setOf(HttpMethod.GET.name(), HttpMethod.POST.name())) return null
            return Target(
                ParticipantFormKind.TIME_USE_DIARY,
                uuid(match.uriVariables.getValue(TimeUseDiaryApi.STUDY_ID)) ?: return null,
                participantId = match.uriVariables.getValue(TimeUseDiaryApi.PARTICIPANT_ID),
            )
        }
        return null
    }

    private fun uuid(value: String): UUID? = runCatching { UUID.fromString(value.trim()) }.getOrNull()

    private data class Target(
        val kind: ParticipantFormKind,
        val studyId: UUID,
        val participantId: String? = null,
        val resourceId: UUID? = null,
    )
}
