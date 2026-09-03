package com.openlattice.chronicle.notifications

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.util.tests.TestDataFactory
import org.junit.Assert
import org.junit.Test

class NotificationSettingsTests : ChronicleServerTests() {

    @Test
    fun testSetAndGetNotificationSettings() {
        val studyId = clientUser1.studyApi.createStudy(TestDataFactory.study())
        val settings = mapOf(
            NotificationType.AUDIT_EVENT to setOf(DeliveryType.EMAIL)
        )
        clientUser1.testNotificationApi.setResearcherNotificationSettings(
            studyId, testUser1.id, settings
        )
        val actual = clientUser1.notificationApi.getResearcherNotificationSettings(
            studyId, testUser1.id
        )
        Assert.assertTrue("Should contain AUDIT_EVENT", actual.containsKey(NotificationType.AUDIT_EVENT))
        Assert.assertTrue(
            "AUDIT_EVENT should have EMAIL delivery",
            actual[NotificationType.AUDIT_EVENT]!!.contains(DeliveryType.EMAIL)
        )
    }

    @Test
    fun testSetNotificationSettingsByType() {
        val studyId = clientUser1.studyApi.createStudy(TestDataFactory.study())
        val deliveryTypes = setOf(DeliveryType.EMAIL, DeliveryType.SMS)
        clientUser1.testNotificationApi.setResearcherNotificationSettingsByType(
            studyId, testUser1.id, NotificationType.ENROLLMENT, deliveryTypes
        )
        val actual = clientUser1.notificationApi.getResearcherNotificationSetting(
            studyId, testUser1.id, NotificationType.ENROLLMENT
        )
        Assert.assertTrue("Should contain EMAIL", actual.contains(DeliveryType.EMAIL))
        Assert.assertTrue("Should contain SMS", actual.contains(DeliveryType.SMS))
    }

    @Test
    fun testGetNotificationSettingsEmpty() {
        val studyId = clientUser1.studyApi.createStudy(TestDataFactory.study())
        val actual = clientUser1.notificationApi.getResearcherNotificationSettings(
            studyId, testUser1.id
        )
        Assert.assertNotNull("Should return non-null result even when no settings configured", actual)
    }

    @Test
    fun testGetNotificationSettingByType() {
        val studyId = clientUser1.studyApi.createStudy(TestDataFactory.study())

        val settings = mapOf(
            NotificationType.AUDIT_EVENT to setOf(DeliveryType.EMAIL),
            NotificationType.ENROLLMENT to setOf(DeliveryType.SMS)
        )
        clientUser1.testNotificationApi.setResearcherNotificationSettings(
            studyId, testUser1.id, settings
        )

        val auditSetting = clientUser1.notificationApi.getResearcherNotificationSetting(
            studyId, testUser1.id, NotificationType.AUDIT_EVENT
        )
        Assert.assertTrue("AUDIT_EVENT should have EMAIL", auditSetting.contains(DeliveryType.EMAIL))
        Assert.assertFalse("AUDIT_EVENT should not have SMS", auditSetting.contains(DeliveryType.SMS))
    }

    @Test
    fun testOverwriteNotificationSettings() {
        val studyId = clientUser1.studyApi.createStudy(TestDataFactory.study())

        val original = mapOf(
            NotificationType.AUDIT_EVENT to setOf(DeliveryType.EMAIL)
        )
        clientUser1.testNotificationApi.setResearcherNotificationSettings(
            studyId, testUser1.id, original
        )

        val updated = mapOf(
            NotificationType.AUDIT_EVENT to setOf(DeliveryType.SMS)
        )
        clientUser1.testNotificationApi.setResearcherNotificationSettings(
            studyId, testUser1.id, updated
        )

        val actual = clientUser1.notificationApi.getResearcherNotificationSettings(
            studyId, testUser1.id
        )
        Assert.assertTrue(
            "Should have SMS after overwrite",
            actual[NotificationType.AUDIT_EVENT]!!.contains(DeliveryType.SMS)
        )
    }

    @Test
    fun testIndependentStudySettings() {
        val studyId1 = clientUser1.studyApi.createStudy(TestDataFactory.study())
        val studyId2 = clientUser1.studyApi.createStudy(TestDataFactory.study())

        val settings1 = mapOf(
            NotificationType.AUDIT_EVENT to setOf(DeliveryType.EMAIL)
        )
        clientUser1.testNotificationApi.setResearcherNotificationSettings(
            studyId1, testUser1.id, settings1
        )

        val actual2 = clientUser1.notificationApi.getResearcherNotificationSettings(
            studyId2, testUser1.id
        )
        Assert.assertFalse(
            "Study2 should not have study1's AUDIT_EVENT settings",
            actual2.containsKey(NotificationType.AUDIT_EVENT) &&
                actual2[NotificationType.AUDIT_EVENT]!!.contains(DeliveryType.EMAIL)
        )
    }
}
