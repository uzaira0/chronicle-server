package com.openlattice.chronicle.services.participantaccess

import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
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
