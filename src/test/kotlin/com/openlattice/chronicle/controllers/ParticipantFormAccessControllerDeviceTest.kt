package com.openlattice.chronicle.controllers

import org.springframework.mock.env.MockEnvironment
import com.openlattice.chronicle.configuration.CookiePolicy
import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.participantaccess.CreateParticipantFormAccessCodeRequest
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.services.studies.ParticipantCollectionAcknowledgmentService
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.audit.RecordingAuditingManager
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import com.openlattice.chronicle.participantaccess.ExchangeParticipantFormAccessCodeRequest
import com.openlattice.chronicle.participantaccess.ParticipantFormAccessCodeResponse
import com.openlattice.chronicle.participantaccess.ParticipantFormSessionResponse
import com.openlattice.chronicle.services.participantaccess.ExchangedParticipantFormSession
import com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeIssuerType
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    private val acknowledgments = Mockito.mock(ParticipantCollectionAcknowledgmentService::class.java)
    private val studyManager = Mockito.mock(StudyManager::class.java)
    private val audit = RecordingAuditingManager()
    private val controller = ParticipantFormAccessController(
        accessService,
        enrollmentManager,
        studyManager,
        acknowledgments,
        AuditService(audit),
        Mockito.mock(AuthorizationManager::class.java),
        Mockito.mock(AuditingManager::class.java),
        cookiePolicy = CookiePolicy(MockEnvironment()),
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
            randomDeviceId = UUID.randomUUID(),
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
    fun deviceCannotMintAFormCodeDuringARequiredModuleDecline() {
        whenever(enrollmentManager.isKnownParticipant(studyId, participantId)).thenReturn(true)
        whenever(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.ENROLLED)
        val device = device()
        whenever(acknowledgments.isCollectionHalted(studyId, participantId, checkNotNull(device.randomDeviceId)))
            .thenReturn(true)

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
        assertEquals(Messages.get("error.enrollment.collectionHalted"), error.reason)
        Mockito.verifyNoInteractions(accessService)
    }

    private fun request(kind: ParticipantFormKind = ParticipantFormKind.APP_USAGE) =
        CreateParticipantFormAccessCodeRequest(kind, null)

    private fun enrolledDevice(): ApiKeyAuthenticationToken {
        whenever(enrollmentManager.isKnownParticipant(studyId, participantId)).thenReturn(true)
        whenever(enrollmentManager.getDeviceParticipationStatus(studyId, participantId))
            .thenReturn(ParticipationStatus.ENROLLED)
        return device()
    }

    /** The 19:00 reminder on an enrolled phone mints the survey link itself, recorded as a device-issued code. */
    @Test
    fun enrolledDeviceMintsItsOwnSurveyCodeAsTheDeviceIssuerWithoutCaching() {
        val device = enrolledDevice()
        val minted = ParticipantFormAccessCodeResponse("raw-code", OffsetDateTime.now().plusDays(1), ParticipantFormKind.APP_USAGE)
        whenever(accessService.createAccessCode(studyId, participantId, ParticipantFormKind.APP_USAGE, null, null, null,
            ParticipantAccessCodeIssuerType.DEVICE, "device:${device.keyId}")).thenReturn(minted)
        val response = MockHttpServletResponse()

        val result = controller.createAccessCode(studyId, participantId, request(), device, response)

        assertEquals(minted, result)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals("no-cache", response.getHeader("Pragma"))
        val event = audit.events.single()
        assertEquals(AuditEventType.CREATE, event.eventType)
        assertEquals(studyId, event.study)
        assertEquals("DEVICE", event.data["issuerType"])
        assertEquals("APP_USAGE", event.data["formKind"])
        assertTrue("participant ids are fingerprinted in audit data", event.data.values.none { it == participantId })
    }

    @Test
    fun formCodesAreRefusedForUnknownParticipantsForeignKeysAndKindsAReminderCannotIssue() {
        whenever(enrollmentManager.isKnownParticipant(studyId, "never-registered")).thenReturn(false)
        val unknown = assertThrows(ResponseStatusException::class.java) {
            controller.createAccessCode(studyId, "never-registered", request(), device(), MockHttpServletResponse())
        }
        assertEquals(404, unknown.statusCode.value())
        assertEquals(Messages.get("error.enrollment.participantNotRegistered"), unknown.reason)

        val device = enrolledDevice()
        val otherStudy = device.copyBoundTo(UUID.randomUUID(), participantId)
        val otherParticipant = device.copyBoundTo(studyId, "someone-else")
        for (foreign in listOf(otherStudy, otherParticipant)) {
            val refused = assertThrows(ResponseStatusException::class.java) {
                controller.createAccessCode(studyId, participantId, request(), foreign, MockHttpServletResponse())
            }
            assertEquals(403, refused.statusCode.value())
            assertEquals(Messages.get("error.enrollment.deviceNotBound"), refused.reason)
        }
        for (kind in ParticipantFormKind.values().toSet() - setOf(ParticipantFormKind.APP_USAGE, ParticipantFormKind.QUESTIONNAIRE)) {
            val refused = assertThrows(ResponseStatusException::class.java) {
                controller.createAccessCode(studyId, participantId, request(kind), device, MockHttpServletResponse())
            }
            assertEquals("$kind", 403, refused.statusCode.value())
            assertEquals(Messages.get("error.form.kindNotIssuableByReminder"), refused.reason)
        }
        Mockito.verifyNoInteractions(accessService)
        assertTrue(audit.events.isEmpty())
    }

    @Test
    fun aCodeTheServiceRefusesAnswers400WithItsReasonAndIsNotAudited() {
        val device = enrolledDevice()
        whenever(accessService.createAccessCode(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any(),
            org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.anyOrNull(),
            org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenThrow(IllegalArgumentException("expiresAt must be in the future"))

        val refused = assertThrows(ResponseStatusException::class.java) {
            controller.createAccessCode(studyId, participantId, request(), device, MockHttpServletResponse())
        }

        assertEquals(400, refused.statusCode.value())
        assertEquals("expiresAt must be in the future", refused.reason)
        assertTrue(audit.events.isEmpty())
    }

    @Test
    fun exchangingAnInvalidOrUsedCodeIsUnauthorizedAndSetsNoCookie() {
        val attempt = UUID.randomUUID()
        whenever(accessService.exchangeAccessCode("used-code", attempt)).thenReturn(null)
        val response = MockHttpServletResponse()

        val refused = assertThrows(ResponseStatusException::class.java) {
            controller.exchangeAccessCode(ExchangeParticipantFormAccessCodeRequest("used-code", attempt), response)
        }

        assertEquals(401, refused.statusCode.value())
        assertEquals(Messages.get("error.form.accessCodeInvalid"), refused.reason)
        assertNull(response.getHeader("Set-Cookie"))
    }

    /** Opening the survey link: a strict, script-proof session cookie that dies with the session, nothing cached. */
    @Test
    fun exchangedCodeBecomesAStrictSessionCookieThatEndsWithTheSession() {
        val attempt = UUID.randomUUID()
        val expiresAt = OffsetDateTime.now().plusMinutes(30)
        val session = ParticipantFormSessionResponse("csrf", studyId, participantId, ParticipantFormKind.APP_USAGE,
            expiresAt = expiresAt)
        whenever(accessService.exchangeAccessCode("fresh-code", attempt))
            .thenReturn(ExchangedParticipantFormSession("raw-session", session))
        whenever(studyManager.getStudySettings(studyId)).thenThrow(NoSuchElementException("study deleted"))
        val response = MockHttpServletResponse()

        val result = controller.exchangeAccessCode(ExchangeParticipantFormAccessCodeRequest("fresh-code", attempt), response)

        assertEquals("a deleted study's session still opens, just without policy links", session, result)
        val cookie = checkNotNull(response.getHeader("Set-Cookie"))
        assertTrue(cookie, cookie.startsWith("${ParticipantFormAccessFilter.SESSION_COOKIE}=raw-session;"))
        for (attribute in listOf("Path=/", "Secure", "HttpOnly", "SameSite=Strict")) {
            assertTrue("$attribute in $cookie", cookie.split("; ").contains(attribute))
        }
        val maxAge = Regex("Max-Age=(\\d+)").find(cookie)!!.groupValues[1].toLong()
        assertTrue("Max-Age $maxAge ends with the session", maxAge in 1795..1800)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals("APP_USAGE", audit.events.single().data["formKind"])

        whenever(accessService.exchangeAccessCode("late-code", attempt)).thenReturn(ExchangedParticipantFormSession(
            "late-session", session.copy(expiresAt = OffsetDateTime.now().minusSeconds(5))))
        val late = MockHttpServletResponse()
        controller.exchangeAccessCode(ExchangeParticipantFormAccessCodeRequest("late-code", attempt), late)
        assertTrue(checkNotNull(late.getHeader("Set-Cookie")).contains("Max-Age=0"))
    }

    /** The settings page greys out policy and withdrawal settings once someone has enrolled; readers may ask. */
    @Test
    fun researcherWithReadAccessSeesWhichSettingsEnrollmentLocked() {
        val authorization = Mockito.mock(AuthorizationManager::class.java)
        TestSecurityUtils.setupAuthorizedResearcher(authorization)
        val reader = ParticipantFormAccessController(accessService, enrollmentManager, studyManager, acknowledgments,
            AuditService(audit), authorization, Mockito.mock(AuditingManager::class.java),
            cookiePolicy = CookiePolicy(MockEnvironment()),
        )
        val locked = listOf(com.openlattice.chronicle.study.StudySettingType.ParticipantPolicy,
            com.openlattice.chronicle.study.StudySettingType.Withdrawal)
        whenever(accessService.getSettingLocks(studyId)).thenReturn(locked)
        try {
            assertEquals(locked, reader.getSettingLocks(studyId))
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext()
        }
    }

    private fun ApiKeyAuthenticationToken.copyBoundTo(study: UUID, participant: String) = ApiKeyAuthenticationToken(
        principal = "apikey:$keyId",
        keyId = keyId,
        studyId = study,
        participantId = participant,
        randomDeviceId = randomDeviceId,
        scope = ApiKeyScope.WRITE,
        authorities = listOf(SimpleGrantedAuthority("ROLE_API_KEY")),
    )

    private fun device(): ApiKeyAuthenticationToken {
        val keyId = UUID.randomUUID()
        return ApiKeyAuthenticationToken(
            principal = "apikey:$keyId",
            keyId = keyId,
            studyId = studyId,
            participantId = participantId,
            randomDeviceId = UUID.randomUUID(),
            scope = ApiKeyScope.WRITE,
            authorities = listOf(SimpleGrantedAuthority("ROLE_API_KEY")),
        )
    }
}
