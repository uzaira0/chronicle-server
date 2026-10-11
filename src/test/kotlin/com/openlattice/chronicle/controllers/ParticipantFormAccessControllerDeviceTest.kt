package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import com.openlattice.chronicle.participantaccess.CreateParticipantFormAccessCodeRequest
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeIssuerType
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.services.studies.StudyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

class ParticipantFormAccessControllerDeviceTest {
    private val accessService = Mockito.mock(ParticipantFormAccessService::class.java)
    private val enrollmentManager = Mockito.mock(EnrollmentManager::class.java)
    private val controller = ParticipantFormAccessController(
        accessService,
        enrollmentManager,
        Mockito.mock(StudyManager::class.java),
        Mockito.mock(AuditService::class.java),
        Mockito.mock(AuthorizationManager::class.java),
        Mockito.mock(AuditingManager::class.java),
    )
    private val studyId = UUID.randomUUID()
    private val participantId = "deleted-participant"

    @Test
    fun deviceCannotMintAFormCodeForAParticipantNoLongerEnrolled() {
        // Researcher deletion sets NOT_ENROLLED before its quarantine hides the row.
        whenever(enrollmentManager.isKnownParticipant(studyId, participantId)).thenReturn(true)
        whenever(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.NOT_ENROLLED)
        val keyId = UUID.randomUUID()
        val device = ApiKeyAuthenticationToken(
            principal = "apikey:$keyId",
            keyId = keyId,
            studyId = studyId,
            participantId = participantId,
            deviceId = UUID.randomUUID(),
            scope = ApiKeyScope.WRITE,
            authorities = listOf(SimpleGrantedAuthority("ROLE_API_KEY")),
        )

        val error = assertThrows(ResponseStatusException::class.java) {
            controller.createAccessCode(
                studyId,
                participantId,
                CreateParticipantFormAccessCodeRequest(ParticipantFormKind.APP_USAGE, null),
                device,
                MockHttpServletResponse(),
            )
        }

        assertEquals(403, error.statusCode.value())
        Mockito.verifyNoInteractions(accessService)
    }
    @Test
    fun researcherEnrollmentIssuanceReplacesThePriorInvitation() {
        TestSecurityUtils.setupSecurityContext()
        try {
            whenever(enrollmentManager.isKnownParticipant(studyId, participantId)).thenReturn(true)
            whenever(controller.authorizationManager.checkIfHasPermissions(org.mockito.kotlin.any(), org.mockito.kotlin.any(),
                org.mockito.kotlin.any())).thenReturn(true)
            val response = com.openlattice.chronicle.participantaccess.ParticipantFormAccessCodeResponse(
                "synthetic-code", java.time.OffsetDateTime.now().plusMinutes(15), ParticipantFormKind.ENROLLMENT)
            val authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().authentication!!
            whenever(accessService.createReplacingAccessCode(studyId, participantId, ParticipantFormKind.ENROLLMENT,
                null, null, null, ParticipantAccessCodeIssuerType.RESEARCHER, authentication.name)).thenReturn(response)
            assertEquals(response, controller.createAccessCode(studyId, participantId,
                CreateParticipantFormAccessCodeRequest(ParticipantFormKind.ENROLLMENT, null),
                authentication, MockHttpServletResponse()))
            Mockito.verify(accessService).createReplacingAccessCode(studyId, participantId, ParticipantFormKind.ENROLLMENT,
                null, null, null, ParticipantAccessCodeIssuerType.RESEARCHER, authentication.name)
            Mockito.verify(accessService, Mockito.never()).createAccessCode(org.mockito.kotlin.any(), org.mockito.kotlin.any(),
                org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.anyOrNull(),
                org.mockito.kotlin.any(), org.mockito.kotlin.any())
        } finally { TestSecurityUtils.clearSecurityContext() }
    }

}
