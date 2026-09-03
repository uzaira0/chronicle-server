package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.notifications.DeliveryType
import com.openlattice.chronicle.notifications.NotificationType
import com.openlattice.chronicle.notifications.ParticipantNotification
import com.openlattice.chronicle.services.notifications.NotificationService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.twilio.TwilioWebhookSignatureVerifier
import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
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

    @Test
    fun testControllerConstructsSuccessfully() {
        assertNotNull(controller)
    }

    // --- getResearcherPhoneNumber ---

    @Test
    fun testGetResearcherPhoneNumberDelegatesToService() {
        val studyId = UUID.randomUUID()
        val principalId = "user-123"
        Mockito.`when`(notificationService.getResearcherPhoneNumber(principalId)).thenReturn("+15551234567")

        val result = controller.getResearcherPhoneNumber(studyId, principalId)
        assertEquals("+15551234567", result)
        verify(notificationService).getResearcherPhoneNumber(principalId)
    }

    @Test
    fun testGetResearcherPhoneNumberReturnsEmptyString() {
        val studyId = UUID.randomUUID()
        val principalId = "user-123"
        Mockito.`when`(notificationService.getResearcherPhoneNumber(principalId)).thenReturn("")

        val result = controller.getResearcherPhoneNumber(studyId, principalId)
        assertEquals("", result)
    }

    @Test(expected = RuntimeException::class)
    fun testGetResearcherPhoneNumberPropagatesException() {
        val studyId = UUID.randomUUID()
        val principalId = "user-123"
        Mockito.`when`(notificationService.getResearcherPhoneNumber(principalId))
            .thenThrow(RuntimeException("error"))

        controller.getResearcherPhoneNumber(studyId, principalId)
    }

    // --- setResearcherPhoneNumber ---

    @Test
    fun testSetResearcherPhoneNumberDelegatesToService() {
        val studyId = UUID.randomUUID()
        val principalId = "user-123"
        val phoneNumber = "+15551234567"

        controller.setResearcherPhoneNumber(studyId, principalId, phoneNumber)
        verify(notificationService).setResearcherPhoneNumber(principalId, phoneNumber)
    }

    @Test(expected = RuntimeException::class)
    fun testSetResearcherPhoneNumberPropagatesException() {
        val studyId = UUID.randomUUID()
        val principalId = "user-123"
        val phoneNumber = "+15551234567"
        Mockito.doThrow(RuntimeException("error"))
            .`when`(notificationService).setResearcherPhoneNumber(principalId, phoneNumber)

        controller.setResearcherPhoneNumber(studyId, principalId, phoneNumber)
    }

    // --- verifyResearcherPhoneNumber ---

    @Test
    fun testVerifyResearcherPhoneNumberDelegatesToService() {
        val studyId = UUID.randomUUID()
        val principalId = "user-123"
        val phoneNumber = "+15551234567"
        val confirmationCode = "123456"

        controller.verifyResearcherPhoneNumber(studyId, principalId, phoneNumber, confirmationCode)
        verify(notificationService).verifyResearcherPhoneNumber(principalId, phoneNumber)
    }

    // --- isResearcherPhoneNumberVerified ---

    @Test
    fun testIsResearcherPhoneNumberVerifiedReturnsTrue() {
        val studyId = UUID.randomUUID()
        val principalId = "user-123"
        val phoneNumber = "+15551234567"
        Mockito.`when`(notificationService.isResearcherPhoneNumberVerified(principalId, phoneNumber))
            .thenReturn(true)

        val result = controller.isResearcherPhoneNumberVerified(studyId, principalId, phoneNumber)
        assertTrue(result)
    }

    @Test
    fun testIsResearcherPhoneNumberVerifiedReturnsFalse() {
        val studyId = UUID.randomUUID()
        val principalId = "user-123"
        val phoneNumber = "+15551234567"
        Mockito.`when`(notificationService.isResearcherPhoneNumberVerified(principalId, phoneNumber))
            .thenReturn(false)

        val result = controller.isResearcherPhoneNumberVerified(studyId, principalId, phoneNumber)
        assertFalse(result)
    }

    @Test(expected = com.geekbeast.controllers.exceptions.ForbiddenException::class)
    fun testNonAdminCannotReadAnotherResearchersPhoneNumber() {
        TestSecurityUtils.setupSecurityContext(subject = "test-user", admin = false)

        controller.getResearcherPhoneNumber(UUID.randomUUID(), "other-user")
    }

    // --- getResearcherNotificationSettings ---

    @Test
    fun testGetResearcherNotificationSettingsDelegatesToService() {
        val studyId = UUID.randomUUID()
        val principalId = "user-123"
        val settings = mapOf<NotificationType, Set<DeliveryType>>()
        Mockito.`when`(notificationService.getResearcherNotificationSettings(studyId, principalId))
            .thenReturn(settings)

        val result = controller.getResearcherNotificationSettings(studyId, principalId)
        assertNotNull(result)
        verify(notificationService).getResearcherNotificationSettings(studyId, principalId)
    }

    @Test
    fun testGetResearcherNotificationSettingsReturnsEmptyMap() {
        val studyId = UUID.randomUUID()
        val principalId = "user-123"
        Mockito.`when`(notificationService.getResearcherNotificationSettings(studyId, principalId))
            .thenReturn(emptyMap())

        val result = controller.getResearcherNotificationSettings(studyId, principalId)
        assertTrue(result.isEmpty())
    }

    // --- setResearcherNotificationSettings (full) ---

    @Test
    fun testSetResearcherNotificationSettingsDelegatesToService() {
        val studyId = UUID.randomUUID()
        val principalId = "user-123"
        val settings = mapOf<NotificationType, Set<DeliveryType>>()

        val result = controller.setResearcherNotificationSettings(studyId, principalId, settings)
        assertEquals(OK.ok, result)
        verify(notificationService).setResearcherNotificationSettings(studyId, principalId, settings)
    }

    @Test
    fun testSetResearcherNotificationSettingsReturnsOk() {
        val studyId = UUID.randomUUID()
        val principalId = "user-123"
        val settings = mapOf<NotificationType, Set<DeliveryType>>()

        val result = controller.setResearcherNotificationSettings(studyId, principalId, settings)
        assertSame(OK.ok, result)
    }

    // --- setResearcherNotificationSettings (per type) ---

    @Test
    fun testSetResearcherNotificationSettingsPerTypeMergesExisting() {
        val studyId = UUID.randomUUID()
        val principalId = "user-123"
        val existingSettings = mutableMapOf<NotificationType, Set<DeliveryType>>()
        Mockito.`when`(notificationService.getResearcherNotificationSettings(studyId, principalId))
            .thenReturn(existingSettings)

        val deliveryTypes = setOf(DeliveryType.EMAIL)
        val notificationType = NotificationType.ENROLLMENT

        val result = controller.setResearcherNotificationSettings(studyId, principalId, notificationType, deliveryTypes)
        assertEquals(OK.ok, result)
    }

    // --- getResearcherNotificationSetting ---

    @Test
    fun testGetResearcherNotificationSettingReturnsDeliveryTypes() {
        val studyId = UUID.randomUUID()
        val principalId = "user-123"
        val notificationType = NotificationType.ENROLLMENT
        val deliveryTypes = setOf(DeliveryType.EMAIL)
        val settings = mapOf(notificationType to deliveryTypes)
        Mockito.`when`(notificationService.getResearcherNotificationSettings(studyId, principalId))
            .thenReturn(settings)

        val result = controller.getResearcherNotificationSetting(studyId, principalId, notificationType)
        assertEquals(deliveryTypes, result)
    }

    @Test
    fun testGetResearcherNotificationSettingReturnsEmptySetForMissing() {
        val studyId = UUID.randomUUID()
        val principalId = "user-123"
        val notificationType = NotificationType.ENROLLMENT
        Mockito.`when`(notificationService.getResearcherNotificationSettings(studyId, principalId))
            .thenReturn(emptyMap())

        val result = controller.getResearcherNotificationSetting(studyId, principalId, notificationType)
        assertTrue(result.isEmpty())
    }

    @Test(expected = com.geekbeast.controllers.exceptions.ForbiddenException::class)
    fun testNonAdminCannotMutateAnotherResearchersNotificationSettings() {
        TestSecurityUtils.setupSecurityContext(subject = "test-user", admin = false)

        controller.setResearcherNotificationSettings(UUID.randomUUID(), "other-user", emptyMap())
    }

    // --- sendNotifications ---

    @Test
    fun testSendNotificationsDelegatesToService() {
        val studyId = UUID.randomUUID()
        val notifications = listOf(Mockito.mock(ParticipantNotification::class.java))
        Mockito.`when`(studyService.isValidStudy(studyId)).thenReturn(true)

        val result = controller.sendNotifications(studyId, notifications)
        assertEquals(OK.ok, result)
        verify(notificationService).sendNotifications(studyId, notifications)
    }

    @Test(expected = IllegalStateException::class)
    fun testSendNotificationsRejectsInvalidStudy() {
        val studyId = UUID.randomUUID()
        val notifications = listOf(Mockito.mock(ParticipantNotification::class.java))
        Mockito.`when`(studyService.isValidStudy(studyId)).thenReturn(false)

        controller.sendNotifications(studyId, notifications)
    }

    @Test
    fun testSendNotificationsWithEmptyList() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(studyService.isValidStudy(studyId)).thenReturn(true)

        val result = controller.sendNotifications(studyId, emptyList())
        assertEquals(OK.ok, result)
    }

    // --- updateNotificationStatus ---

    @Test
    fun testUpdateNotificationStatusDelegatesToService() {
        val messageId = "SM12345"
        val messageStatus = "delivered"

        val result = controller.updateNotificationStatus(messageId, messageStatus)
        assertEquals(OK.ok, result)
        verify(notificationService).updateNotificationStatus(messageId, messageStatus)
    }

    @Test
    fun testUpdateNotificationStatusReturnsOk() {
        val result = controller.updateNotificationStatus("SM123", "sent")
        assertSame(OK.ok, result)
    }

    @Test(expected = RuntimeException::class)
    fun testUpdateNotificationStatusPropagatesException() {
        Mockito.doThrow(RuntimeException("update error"))
            .`when`(notificationService).updateNotificationStatus(kAnyString(), kAnyString())

        controller.updateNotificationStatus("SM123", "sent")
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

    @Test(expected = org.springframework.web.server.ResponseStatusException::class)
    fun testUpdateNotificationStatusRejectsInvalidTwilioSignature() {
        Mockito.`when`(twilioWebhookSignatureVerifier.isCurrentRequestValid()).thenReturn(false)

        controller.updateNotificationStatus("SM123", "sent")
    }
}
