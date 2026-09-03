package com.openlattice.chronicle.filters

import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessScope
import com.openlattice.chronicle.storage.rls.RLSConnectionContext
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/** Fail-closed capability gate for the otherwise anonymous participant web-form surface. */
public class ParticipantFormAccessFilter(
    private val participantFormAccessService: ParticipantFormAccessService,
) : OncePerRequestFilter() {
    public companion object {
        public const val SESSION_COOKIE: String = "__Host-chronicle-form"
        public const val CSRF_HEADER: String = "X-Chronicle-Form-CSRF"
        public const val IDEMPOTENCY_HEADER: String = "Idempotency-Key"
        public const val IDEMPOTENCY_ATTRIBUTE: String = "chronicle.participantFormIdempotencyKey"
        public const val REQUEST_SCOPE_ATTRIBUTE: String = "chronicle.participantFormScope"

        public fun currentScope(): ParticipantFormAccessScope? =
            RequestContextHolder.getRequestAttributes()
                ?.getAttribute(REQUEST_SCOPE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) as? ParticipantFormAccessScope

        public fun currentIdempotencyKey(): UUID? =
            RequestContextHolder.getRequestAttributes()
                ?.getAttribute(IDEMPOTENCY_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) as? UUID

        private val SURVEY_PARTICIPANT = Regex(
            """^/chronicle/v3/survey/([0-9a-fA-F-]+)/participant/([^/]+)/(app-usage|device-usage)$"""
        )
        private val SURVEY_FREQUENCY = Regex(
            """^/chronicle/v3/survey/([0-9a-fA-F-]+)/app-usage-frequency$"""
        )
        private val QUESTIONNAIRE_READ = Regex(
            """^/chronicle/v3/survey/([0-9a-fA-F-]+)/questionnaire/([0-9a-fA-F-]+)$"""
        )
        private val QUESTIONNAIRE_SUBMIT = Regex(
            """^/chronicle/v3/survey/([0-9a-fA-F-]+)/participant/([^/]+)/questionnaire/([0-9a-fA-F-]+)$"""
        )
        private val TUD_SETTINGS = Regex(
            """^/chronicle/v3/time-use-diary/([0-9a-fA-F-]+)/settings$"""
        )
        private val TUD_PARTICIPANT = Regex(
            """^/chronicle/v3/time-use-diary/([0-9a-fA-F-]+)/participant/([^/]+)$"""
        )
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = target(request) == null

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val target = target(request) ?: run {
            filterChain.doFilter(request, response)
            return
        }
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication?.isAuthenticated == true && authentication !is AnonymousAuthenticationToken) {
            filterChain.doFilter(request, response)
            return
        }

        val rawSession = request.cookies?.firstOrNull { it.name == SESSION_COOKIE }?.value
        val requireCsrf = request.method !in setOf(HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name())
        val scope = rawSession?.let {
            participantFormAccessService.resolveSession(it, request.getHeader(CSRF_HEADER), requireCsrf)
        }
        if (scope == null || !scope.permits(target.kind, target.studyId, target.participantId, target.resourceId)) {
            ChronicleMetrics.participantFormAccessTotal.labels(target.kind.name, "rejected").inc()
            response.sendError(HttpStatus.UNAUTHORIZED.value(), Messages.get("error.form.sessionInvalid", request))
            return
        }

        if (requireCsrf) {
            val idempotencyKey = request.getHeader(IDEMPOTENCY_HEADER)?.let {
                runCatching { UUID.fromString(it) }.getOrNull()
            }
            if (idempotencyKey == null) {
                response.sendError(
                    HttpStatus.BAD_REQUEST.value(),
                    Messages.get("error.form.idempotencyKeyRequired", request),
                )
                return
            }
            request.setAttribute(IDEMPOTENCY_ATTRIBUTE, idempotencyKey)
        }

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

    private fun target(request: HttpServletRequest): Target? {
        val path = request.requestURI
        SURVEY_PARTICIPANT.matchEntire(path)?.let { match ->
            if (request.method !in setOf(HttpMethod.GET.name(), HttpMethod.POST.name())) return null
            return Target(
                kind = ParticipantFormKind.APP_USAGE,
                studyId = uuid(match.groupValues[1]) ?: return null,
                participantId = decodePathSegment(match.groupValues[2]),
            )
        }
        SURVEY_FREQUENCY.matchEntire(path)?.let { match ->
            if (request.method != HttpMethod.GET.name()) return null
            return Target(ParticipantFormKind.APP_USAGE, uuid(match.groupValues[1]) ?: return null)
        }
        QUESTIONNAIRE_READ.matchEntire(path)?.let { match ->
            if (request.method != HttpMethod.GET.name()) return null
            return Target(
                ParticipantFormKind.QUESTIONNAIRE,
                uuid(match.groupValues[1]) ?: return null,
                resourceId = uuid(match.groupValues[2]) ?: return null,
            )
        }
        QUESTIONNAIRE_SUBMIT.matchEntire(path)?.let { match ->
            if (request.method != HttpMethod.POST.name()) return null
            return Target(
                ParticipantFormKind.QUESTIONNAIRE,
                uuid(match.groupValues[1]) ?: return null,
                participantId = decodePathSegment(match.groupValues[2]),
                resourceId = uuid(match.groupValues[3]) ?: return null,
            )
        }
        TUD_SETTINGS.matchEntire(path)?.let { match ->
            if (request.method != HttpMethod.GET.name()) return null
            return Target(ParticipantFormKind.TIME_USE_DIARY, uuid(match.groupValues[1]) ?: return null)
        }
        TUD_PARTICIPANT.matchEntire(path)?.let { match ->
            if (request.method !in setOf(HttpMethod.GET.name(), HttpMethod.POST.name())) return null
            return Target(
                ParticipantFormKind.TIME_USE_DIARY,
                uuid(match.groupValues[1]) ?: return null,
                participantId = decodePathSegment(match.groupValues[2]),
            )
        }
        return null
    }

    private fun uuid(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

    private fun decodePathSegment(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8)

    private data class Target(
        val kind: ParticipantFormKind,
        val studyId: UUID,
        val participantId: String? = null,
        val resourceId: UUID? = null,
    )
}
