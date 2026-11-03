package com.openlattice.chronicle.controllers

import com.geekbeast.retrofit.RhizomeRetrofitCallException
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.UsageEnrollmentFixtures
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.util.tests.TestDataFactory
import com.openlattice.chronicle.webhooks.WebhookEventType
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadOutboxIntegrationTest : ChronicleServerTests() {
    @Test fun aFailedOutboxInsertRollsBackTheUploadAndAnIdenticalRetryCommitsBoth() {
        val study = clientUser1.studyApi.createStudy(Study(title = "atomic ingest outbox", contact = "test@example.org"))
        UsageEnrollmentFixtures.prepareUsageFixture(clientUser1, study)
        val subject = TestDataFactory.participant()
        val participant = subject.participantId
        clientUser1.studyApi.registerParticipant(study, subject)
        val device = TestDataFactory.androidDevice()
        val enrollment = UsageEnrollmentFixtures.enrollUsageFixture(study, participant, device)
        val batch = TestDataFactory.chronicleUsageEvents(study, participant, count = 1)
        installFault(study)
        try {
            val failed = assertThrows(RhizomeRetrofitCallException::class.java) {
                enrollment.mobileApi.uploadAndroidUsageEventDataV4(study, participant, device.randomDeviceId, batch)
            }
            assertEquals(500, failed.code)
            assertTrue(UsageEnrollmentFixtures.storedUsageTimestamps(study, participant).isEmpty())
            assertEquals(0, deliveryCount(study))
            hds.connection.use { connection ->
                connection.prepareStatement("UPDATE fixture_upload_outbox_fault SET fail_insert = false WHERE study_id = ?").use {
                    it.setObject(1, study); assertEquals(1, it.executeUpdate())
                }
            }
            assertEquals(1, enrollment.mobileApi.uploadAndroidUsageEventDataV4(study, participant, device.randomDeviceId, batch).acceptedIds.size)
            assertEquals(1, UsageEnrollmentFixtures.storedUsageTimestamps(study, participant).size)
            assertEquals(1, deliveryCount(study))
        } finally {
            hds.connection.use { connection -> connection.createStatement().use {
                it.execute("DROP TRIGGER fixture_upload_outbox_fault ON webhook_deliveries; " +
                    "DROP FUNCTION fixture_upload_outbox_fault(); DROP TABLE fixture_upload_outbox_fault")
            } }
        }
    }

    private fun installFault(study: UUID) {
        hds.connection.use { connection ->
            val sql = checkNotNull(javaClass.getResourceAsStream("/faults/upload-outbox.sql")).bufferedReader().use { it.readText() }
            connection.createStatement().use { it.execute(sql) }
            connection.prepareStatement("INSERT INTO fixture_upload_outbox_fault VALUES (?, true)").use {
                it.setObject(1, study); it.executeUpdate()
            }
            connection.prepareStatement("""
                INSERT INTO webhook_registrations (study_id, url, secret_hash, event_types, created_by)
                VALUES (?, 'https://example.org/held-fixture', 'fixture-secret', ?, 'fixture-researcher')
            """.trimIndent()).use {
                it.setObject(1, study)
                it.setArray(2, connection.createArrayOf("text", arrayOf(WebhookEventType.DATA_SUBMITTED.name)))
                it.executeUpdate()
            }
        }
    }

    private fun deliveryCount(study: UUID): Int = hds.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM webhook_deliveries WHERE study_id = ?").use {
            it.setObject(1, study)
            it.executeQuery().use { rows -> assertTrue(rows.next()); rows.getInt(1) }
        }
    }
}
