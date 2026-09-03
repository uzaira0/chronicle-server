package com.openlattice.chronicle.services.enrollment

import com.openlattice.chronicle.audit.AuditLogEntry
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.participantaccess.ParticipantFormAccessCodeResponse
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeIssuerType
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.study.EnrollmentPreviewResponse
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyLifecycleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ReviewerEnrollmentServiceTest {
    private val studyId = UUID.fromString("00000000-0000-0000-0000-000000000401")
    private val participantId = "play-reviewer"
    private val now = OffsetDateTime.parse("2026-08-17T15:00:00Z")
    private val clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC)
    private val accessService = Mockito.mock(ParticipantFormAccessService::class.java)
    private val manifestService = Mockito.mock(EnrollmentManifestService::class.java)
    private val enrollmentManager = Mockito.mock(EnrollmentManager::class.java)
    private val studyService = Mockito.mock(StudyService::class.java)
    private val studyLifecycleService = Mockito.mock(com.openlattice.chronicle.services.studies.StudyLifecycleService::class.java)
    private val auditService = Mockito.mock(AuditService::class.java)

    @Test
    fun `each bootstrap replaces the prior reviewer invite and returns a fresh one-time code manifest pair`() {
        val firstCode = "a".repeat(64)
        val secondCode = "b".repeat(64)
        val firstPreview = Mockito.mock(EnrollmentPreviewResponse::class.java)
        val secondPreview = Mockito.mock(EnrollmentPreviewResponse::class.java)
        whenever(studyService.getStudy(studyId)).thenReturn(activeStudy())
        whenever(enrollmentManager.isKnownParticipant(studyId, participantId)).thenReturn(true)
        whenever(enrollmentManager.getParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.ENROLLED)
        whenever(
            accessService.createReplacingAccessCode(
                eq(studyId),
                eq(participantId),
                eq(ParticipantFormKind.ENROLLMENT),
                eq(null),
                eq(null),
                eq(now.plusMinutes(15)),
                eq(ParticipantAccessCodeIssuerType.RESEARCHER),
                eq(ReviewerEnrollmentService.ISSUED_BY),
            ),
        ).thenReturn(
            accessCode(firstCode),
            accessCode(secondCode),
        )
        whenever(manifestService.getPreview(studyId, participantId, firstCode)).thenReturn(firstPreview)
        whenever(manifestService.getPreview(studyId, participantId, secondCode)).thenReturn(secondPreview)
        val service = service()

        val first = service.mint()
        val second = service.mint()

        assertEquals(firstCode, first.enrollmentCode)
        assertEquals(firstPreview, first.preview)
        assertEquals(secondCode, second.enrollmentCode)
        assertEquals(secondPreview, second.preview)
        assertNotEquals(first.enrollmentCode, second.enrollmentCode)
        verify(accessService, Mockito.times(2)).createReplacingAccessCode(
            studyId,
            participantId,
            ParticipantFormKind.ENROLLMENT,
            null,
            null,
            now.plusMinutes(15),
            ParticipantAccessCodeIssuerType.RESEARCHER,
            ReviewerEnrollmentService.ISSUED_BY,
        )
        val auditCaptor = argumentCaptor<AuditLogEntry>()
        verify(auditService, Mockito.times(2)).log(auditCaptor.capture())
        auditCaptor.allValues.forEach { entry ->
            assertEquals(studyId, entry.studyId)
            assertEquals(true, entry.success)
            assertFalse(entry.toString().contains(participantId))
            assertFalse(entry.toString().contains(firstCode))
            assertFalse(entry.toString().contains(secondCode))
        }
    }

    @Test
    fun `missing configured participant fails closed without issuing a code`() {
        whenever(studyService.getStudy(studyId)).thenReturn(activeStudy())
        whenever(enrollmentManager.isKnownParticipant(studyId, participantId)).thenReturn(false)

        val exception = assertThrows(ResponseStatusException::class.java) { service().mint() }

        assertEquals(404, exception.statusCode.value())
        verify(accessService, never()).createReplacingAccessCode(
            any(), any(), any(), any(), any(), any(), any(), any(),
        )
    }

    @Test
    fun `inactive study or participant cannot mint reviewer enrollment`() {
        whenever(studyService.getStudy(studyId)).thenReturn(
            activeStudy().let { study ->
                Study(
                    studyId = study.id,
                    title = study.title,
                    contact = study.contact,
                    startedAt = now.minusDays(5),
                    endedAt = now.minusSeconds(1),
                    settings = study.settings,
                )
            },
        )

        val expiredStudy = assertThrows(ResponseStatusException::class.java) { service().mint() }
        assertEquals(409, expiredStudy.statusCode.value())

        whenever(studyService.getStudy(studyId)).thenReturn(activeStudy())
        whenever(enrollmentManager.isKnownParticipant(studyId, participantId)).thenReturn(true)
        whenever(enrollmentManager.getParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.PAUSED)

        val pausedParticipant = assertThrows(ResponseStatusException::class.java) { service().mint() }
        assertEquals(409, pausedParticipant.statusCode.value())
        verify(accessService, never()).createReplacingAccessCode(
            any(), any(), any(), any(), any(), any(), any(), any(),
        )
    }

    @Test
    fun `not enrolled reviewer fails startup validation and cannot mint an unusable credential`() {
        whenever(studyService.getStudy(studyId)).thenReturn(activeStudy())
        whenever(enrollmentManager.isKnownParticipant(studyId, participantId)).thenReturn(true)
        whenever(enrollmentManager.getParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.NOT_ENROLLED)
        val service = service()

        val startupFailure = assertThrows(ResponseStatusException::class.java) {
            service.validateConfiguredScope()
        }
        val requestFailure = assertThrows(ResponseStatusException::class.java) { service.mint() }

        assertEquals(409, startupFailure.statusCode.value())
        assertEquals(409, requestFailure.statusCode.value())
        verify(accessService, never()).createReplacingAccessCode(
            any(), any(), any(), any(), any(), any(), any(), any(),
        )
    }

    @Test
    fun `archived reviewer study cannot mint even while its dates and participant remain active`() {
        whenever(studyService.getStudy(studyId)).thenReturn(activeStudy())
        whenever(studyLifecycleService.getLifecycleStatus(studyId)).thenReturn(StudyLifecycleStatus.ARCHIVED)
        whenever(enrollmentManager.isKnownParticipant(studyId, participantId)).thenReturn(true)
        whenever(enrollmentManager.getParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.ENROLLED)

        val exception = assertThrows(ResponseStatusException::class.java) { service(stubActiveLifecycle = false).mint() }

        assertEquals(409, exception.statusCode.value())
        verify(accessService, never()).createReplacingAccessCode(
            any(), any(), any(), any(), any(), any(), any(), any(),
        )
    }

    private fun service(stubActiveLifecycle: Boolean = true): ReviewerEnrollmentService {
        if (stubActiveLifecycle) {
            whenever(studyLifecycleService.getLifecycleStatus(studyId)).thenReturn(StudyLifecycleStatus.ACTIVE)
        }
        return ReviewerEnrollmentService(
            ReviewerEnrollmentScope(studyId, participantId),
            accessService,
            manifestService,
            enrollmentManager,
            studyService,
            studyLifecycleService,
            auditService,
            clock,
        )
    }

    private fun activeStudy(): Study = Study(
        studyId = studyId,
        title = "Play review study",
        contact = "review@example.org",
        startedAt = now.minusDays(1),
        endedAt = now.plusDays(30),
    )

    private fun accessCode(value: String): ParticipantFormAccessCodeResponse =
        ParticipantFormAccessCodeResponse(
            accessCode = value,
            expiresAt = now.plusMinutes(15),
            formKind = ParticipantFormKind.ENROLLMENT,
        )
}
