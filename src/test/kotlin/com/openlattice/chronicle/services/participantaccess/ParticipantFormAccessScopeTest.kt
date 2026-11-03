package com.openlattice.chronicle.services.participantaccess

import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import kotlin.reflect.full.primaryConstructor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class ParticipantFormAccessScopeTest {
    private val studyId = UUID.randomUUID()
    private val participantId = "participant:test"
    private val questionnaireId = UUID.randomUUID()

    @Test
    fun questionnaireScopeIsSealedToItsSubjectAndResource() {
        val scope = scope(ParticipantFormKind.QUESTIONNAIRE, questionnaireId)

        assertTrue(scope.permits(ParticipantFormKind.QUESTIONNAIRE, studyId, participantId, questionnaireId))
        assertFalse(scope.permits(ParticipantFormKind.QUESTIONNAIRE, studyId, "participant:other", questionnaireId))
        assertFalse(scope.permits(ParticipantFormKind.QUESTIONNAIRE, studyId, participantId, UUID.randomUUID()))
        assertFalse(scope.permits(ParticipantFormKind.APP_USAGE, studyId, participantId, null))
    }

    @Test
    fun portalScopeMayReachFormsButNeverAnotherParticipantOrStudy() {
        val scope = scope(ParticipantFormKind.PORTAL, null)

        assertTrue(scope.permits(ParticipantFormKind.APP_USAGE, studyId, participantId, null))
        assertTrue(scope.permits(ParticipantFormKind.TIME_USE_DIARY, studyId, participantId, null))
        assertFalse(scope.permits(ParticipantFormKind.APP_USAGE, UUID.randomUUID(), participantId, null))
        assertFalse(scope.permits(ParticipantFormKind.APP_USAGE, studyId, "participant:other", null))
    }

    @Test
    fun everyAuthorizationClaimHasANegativeCase() {
        val day = LocalDate.parse("2026-10-17")
        val valid = scope(ParticipantFormKind.QUESTIONNAIRE, questionnaireId).copy(logicalDate = day)
        val start = day.atStartOfDay().atOffset(ZoneOffset.ofHours(-6))
        val window = ParticipantFormWindow(start, start.plusDays(1))
        fun permits(candidate: ParticipantFormAccessScope): Boolean = candidate.permits(
            ParticipantFormKind.QUESTIONNAIRE, studyId, participantId, questionnaireId, window = window,
        )
        val negativeCases = mapOf(
            "studyId" to valid.copy(studyId = UUID.randomUUID()),
            "participantId" to valid.copy(participantId = "other-participant"),
            "formKind" to valid.copy(formKind = ParticipantFormKind.APP_USAGE),
            "resourceId" to valid.copy(resourceId = UUID.randomUUID()),
            "logicalDate" to valid.copy(logicalDate = day.minusDays(1)),
        )
        // These two stored-session fields are verified at resolution; the database regressions
        // delete the issuing code and expire the session before resolving its credential again.
        val resolvedSessionFields = setOf("accessCodeId", "absoluteExpiresAt")
        val fields = checkNotNull(ParticipantFormAccessScope::class.primaryConstructor).parameters.map { it.name }.toSet()
        assertEquals(fields, negativeCases.keys + resolvedSessionFields)
        assertTrue(permits(valid))
        negativeCases.forEach { (field, invalid) -> assertFalse("Ignored scope claim: $field", permits(invalid)) }
    }

    @Test
    fun datedWindowsHonorExclusiveMidnightAndParticipantLocalOffsets() {
        val day = LocalDate.parse("2026-10-17")
        val dated = scope(ParticipantFormKind.APP_USAGE, null).copy(logicalDate = day)
        for (offset in listOf(-6, 0, 14)) {
            val start = day.atStartOfDay().atOffset(ZoneOffset.ofHours(offset))
            assertTrue(dated.permits(ParticipantFormKind.APP_USAGE, studyId, participantId, null,
                ParticipantFormWindow(start, start.plusDays(1))))
            assertFalse(dated.permits(ParticipantFormKind.APP_USAGE, studyId, participantId, null,
                ParticipantFormWindow(start, start.plusDays(1).plusNanos(1))))
            assertFalse(dated.permits(ParticipantFormKind.APP_USAGE, studyId, participantId, null,
                ParticipantFormWindow(start.minusDays(1), start)))
            assertFalse(dated.permits(ParticipantFormKind.APP_USAGE, studyId, participantId, null,
                ParticipantFormWindow(start.plusHours(1), start)))
        }
        val dstDay = LocalDate.parse("2026-11-01")
        val dstScope = dated.copy(logicalDate = dstDay)
        assertTrue(dstScope.permits(ParticipantFormKind.APP_USAGE, studyId, participantId, null,
            ParticipantFormWindow(OffsetDateTime.parse("2026-11-01T00:00:00-04:00"), OffsetDateTime.parse("2026-11-02T00:00:00-05:00"))))
    }

    private fun scope(kind: ParticipantFormKind, resourceId: UUID?) = ParticipantFormAccessScope(
        accessCodeId = UUID.randomUUID(),
        studyId = studyId,
        participantId = participantId,
        formKind = kind,
        resourceId = resourceId,
        logicalDate = null,
        absoluteExpiresAt = OffsetDateTime.now().plusHours(1),
    )
}
