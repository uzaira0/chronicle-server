package com.openlattice.chronicle.controllers

import org.junit.Assert.assertThrows

import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.notifications.ParticipantNotification
import com.openlattice.chronicle.services.notifications.NotificationService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.twilio.TwilioWebhookSignatureVerifier
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.springframework.web.server.ResponseStatusException
import java.util.*

class NotificationControllerTest {

    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private val idGenerationService = Mockito.mock(HazelcastIdGenerationService::class.java)
    private val notificationService = Mockito.mock(NotificationService::class.java)
    private val studyService = Mockito.mock(StudyService::class.java)
    private val twilioWebhookSignatureVerifier = Mockito.mock(TwilioWebhookSignatureVerifier::class.java)
    private val auditingManager = Mockito.mock(AuditingManager::class.java)
    private val authorizationManager = Mockito.mock(AuthorizationManager::class.java)

    private lateinit var controller: NotificationController

    @Before
    fun setUp() {
        TestSecurityUtils.setupSecurityContext()

        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)

        controller = NotificationController(
            storageResolver, idGenerationService, notificationService,
            studyService, twilioWebhookSignatureVerifier, auditingManager, authorizationManager
        )
        Mockito.`when`(twilioWebhookSignatureVerifier.isCurrentRequestValid()).thenReturn(true)
    }

    

    // --- getResearcherPhoneNumber ---

    

    

    

    // --- setResearcherPhoneNumber ---

    

    

    // --- verifyResearcherPhoneNumber ---

    

    // --- isResearcherPhoneNumberVerified ---

    

    

    

    // --- getResearcherNotificationSettings ---

    

    

    // --- setResearcherNotificationSettings (full) ---

    

    

    // --- setResearcherNotificationSettings (per type) ---

    

    // --- getResearcherNotificationSetting ---

    

    

    

    // --- sendNotifications ---

    @Test
    fun disabledParticipantNotificationsNeverReportSuccess() {
        val studyId = UUID.randomUUID()
        val notifications = listOf(Mockito.mock(ParticipantNotification::class.java))
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)

        val refused = assertThrows(ResponseStatusException::class.java) {
            controller.sendNotifications(studyId, notifications)
        }
        assertEquals(501, refused.statusCode.value())
        assertEquals(Messages.get("error.notification.deliveryUnsupported"), refused.reason)
        Mockito.verifyNoInteractions(notificationService)
    }

    @Test
    fun testSendNotificationsRejectsInvalidStudy() {
        val studyId = UUID.randomUUID()
        val notifications = listOf(Mockito.mock(ParticipantNotification::class.java))
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(null)

        assertThrows(com.openlattice.chronicle.exceptions.StudyNotFoundException::class.java) {
            controller.sendNotifications(studyId, notifications)
        }
    }

    @Test
    fun testSendNotificationsWithEmptyList() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)

        val refused = assertThrows(ResponseStatusException::class.java) { controller.sendNotifications(studyId, emptyList()) }
        assertEquals(501, refused.statusCode.value())
    }

    // --- updateNotificationStatus ---

    @Test
    fun testUpdateNotificationStatusDelegatesToService() {
        val messageId = "SM12345"
        val messageStatus = "delivered"
        val caller = RLSRequestContext.current()
        Mockito.doAnswer {
            assertTrue(RLSRequestContext.current()?.isAdmin == true)
            null
        }.`when`(notificationService).updateNotificationStatus(messageId, messageStatus)

        val result = controller.updateNotificationStatus(messageId, messageStatus)
        assertEquals(OK.ok, result)
        verify(notificationService).updateNotificationStatus(messageId, messageStatus)
        assertEquals(caller, RLSRequestContext.current())
    }

    @Test
    fun testUpdateNotificationStatusReturnsOk() {
        val result = controller.updateNotificationStatus("SM123", "sent")
        assertSame(OK.ok, result)
    }

    @Test
    fun testUpdateNotificationStatusPropagatesException() {
        Mockito.doThrow(RuntimeException("update error"))
            .`when`(notificationService).updateNotificationStatus(kAnyString(), kAnyString())

        assertThrows(RuntimeException::class.java) {
            controller.updateNotificationStatus("SM123", "sent")
        }
    }

    @Test
    fun testUpdateNotificationStatusWithDifferentStatuses() {
        controller.updateNotificationStatus("SM1", "queued")
        verify(notificationService).updateNotificationStatus("SM1", "queued")

        controller.updateNotificationStatus("SM2", "delivered")
        verify(notificationService).updateNotificationStatus("SM2", "delivered")

        controller.updateNotificationStatus("SM3", "failed")
        verify(notificationService).updateNotificationStatus("SM3", "failed")
    }

    @Test
    fun testUpdateNotificationStatusRejectsInvalidTwilioSignature() {
        Mockito.`when`(twilioWebhookSignatureVerifier.isCurrentRequestValid()).thenReturn(false)

        assertThrows(ResponseStatusException::class.java) {
            controller.updateNotificationStatus("SM123", "sent")
        }
    }
}
