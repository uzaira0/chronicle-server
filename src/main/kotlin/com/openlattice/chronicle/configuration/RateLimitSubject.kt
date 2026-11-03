package com.openlattice.chronicle.configuration

import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.survey.SurveyApi
import com.openlattice.chronicle.timeusediary.TimeUseDiaryApi
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.util.matchingSegments
import com.openlattice.chronicle.util.parsedPath
import jakarta.servlet.http.HttpServletRequest
import java.util.UUID

/** Quotas consume the same identity authentication established, never its URI spelling. */
internal data class RateLimitSubject(val studyId: UUID?, val participantId: String?) {
    fun quotaKey(strategy: RateLimitKeyStrategy, clientRef: String): String = when (strategy) {
        RateLimitKeyStrategy.IP -> clientRef
        RateLimitKeyStrategy.PARTICIPANT_STUDY ->
            "participant:${studyReference()}:${stableRefOrUnknown(participantId ?: "unknown", "participant")}:$clientRef"
    }

    private fun studyReference(): String = stableRefOrUnknown(studyId?.toString() ?: "unknown", "study")

    private fun stableRefOrUnknown(value: String, prefix: String): String =
        if (value == "unknown") "$prefix:unknown" else LogSanitizer.stableFingerprint(value, prefix = prefix)

    companion object {
        private val studySegments = listOf(StudyApi.CONTROLLER, SurveyApi.CONTROLLER, TimeUseDiaryApi.CONTROLLER)
            .mapTo(hashSetOf()) { it.substringAfterLast('/') }
        private val participantSegment = StudyApi.PARTICIPANT_PATH.removePrefix("/")
        private const val ATTRIBUTE = "chronicle.rateLimitSubject"

        /** RateLimitFilter skips authenticated requests, so the path names the subject. */
        fun from(request: HttpServletRequest): RateLimitSubject {
            (request.getAttribute(ATTRIBUTE) as? RateLimitSubject)?.let { return it }
            val segments = request.parsedPath()?.matchingSegments()?.zipWithNext().orEmpty()
            val subject = RateLimitSubject(
                segments.firstOrNull { it.first in studySegments }?.second?.let {
                    runCatching { UUID.fromString(it.trim()) }.getOrNull()
                },
                segments.firstOrNull { it.first == participantSegment }?.second,
            )
            request.setAttribute(ATTRIBUTE, subject)
            return subject
        }
    }
}
