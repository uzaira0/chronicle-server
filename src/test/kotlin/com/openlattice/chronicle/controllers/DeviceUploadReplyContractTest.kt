package com.openlattice.chronicle.controllers

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.ChronicleServerTests.Companion.clientUser1
import com.openlattice.chronicle.ChronicleServerTests.Companion.testHttpPort
import com.openlattice.chronicle.UsageEnrollmentFixtures.UsageEnrollmentFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.acknowledgeUsageFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.beginUsageEnrollmentFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.prepareUsageFixture
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.InteractionPointerCaptureCapability
import com.openlattice.chronicle.client.updateParticipationStatusAsRead
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.generated.ChronicleHeaders
import com.openlattice.chronicle.generated.CollectionConsentContracts
import com.openlattice.chronicle.generated.UploadLimits
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.util.API_KEY_HEADER
import com.openlattice.chronicle.util.tests.TestDataFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

private val mapper = ObjectMappers.newJsonMapper()
private val http = HttpClient.newHttpClient()

private fun beginEnrollment(title: String): UsageEnrollmentFixture {
    val studyId = clientUser1.studyApi.createStudy(Study(title = title, contact = "tests@openlattice.com"))
    prepareUsageFixture(clientUser1, studyId)
    val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
    clientUser1.studyApi.registerParticipant(studyId, participant)
    return beginUsageEnrollmentFixture(studyId, participant.participantId, TestDataFactory.androidDevice())
}

private fun devicePost(
    enrollment: UsageEnrollmentFixture,
    path: String,
    body: String,
    participantId: String = enrollment.replay.participantId,
): HttpResponse<String> = http.send(
    HttpRequest.newBuilder(URI("http://localhost:$testHttpPort${StudyApi.V4_BASE}/${enrollment.replay.studyId}" +
        "${StudyApi.PARTICIPANT_PATH}/$participantId${StudyApi.ANDROID_PATH}$path"))
        .header(API_KEY_HEADER, enrollment.replay.proposedApiKey)
        .header(ChronicleHeaders.RANDOM_DEVICE_ID_HEADER, enrollment.replay.device.randomDeviceId)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build(),
    HttpResponse.BodyHandlers.ofString(),
)

private fun assertRequestRejection(response: HttpResponse<String>, status: Int = 400) {
    assertEquals(response.body(), status, response.statusCode())
    assertFalse(response.body(), response.body().contains(UploadLimits.UPLOAD_ROW_REJECTED_CODE))
}

private fun replyCode(response: HttpResponse<String>): String? = mapper.readTree(response.body()).path("code").textValue()

/** The phone drops a queued acknowledgment only on this reply. */
private fun assertAckRejected(response: HttpResponse<String>) {
    assertEquals(response.body(), 400, response.statusCode())
    assertEquals(response.body(), CollectionConsentContracts.COLLECTION_ACK_REJECTED_CODE, replyCode(response))
}

/** Any other 400 keeps the acknowledgment queued and retried. */
private fun assertAckKept(response: HttpResponse<String>) {
    assertEquals(response.body(), 400, response.statusCode())
    assertNull(response.body(), replyCode(response))
}

/** Request failures must leave the Android batch queued rather than identify a rejected row. */
class DeviceUploadReplyContractTest : ChronicleServerTests() {
    private fun battery(levelPercent: Int) = """
        {"id":"${UUID.randomUUID()}","timestamp":"2026-10-29T12:00:00Z","timezone":"America/Santiago",
         "levelPercent":$levelPercent,"chargingState":"CHARGING","plugType":"AC","temperatureDeciC":250,
         "voltageMillivolts":4000,"health":"GOOD"}
    """.trimIndent()

    private fun diagnostic(count: Int) = """
        [{"id":"${UUID.randomUUID()}","day":"2026-10-29","moduleFamily":"BATTERY","issueCode":"SAMPLE_QUARANTINED",
          "count":$count,"firstOccurredAt":"2026-10-29T12:00:00Z","lastOccurredAt":"2026-10-29T12:00:00Z"}]
    """.trimIndent()

    @Test
    fun deviceUploadToAnInvalidPathParticipantIdCarriesNoRowCode() {
        val enrollment = beginEnrollment("Invalid upload path participant")
        acknowledgeUsageFixture(enrollment)
        val body = "[${battery(50)}]"
        assertEquals(200, devicePost(enrollment, StudyApi.BATTERY_PATH, body).statusCode())

        // The authenticated key is bound to the real participant; '~' also fails the participant-id syntax.
        assertRequestRejection(devicePost(enrollment, StudyApi.BATTERY_PATH, body, participantId = "bad~id"), 403)
    }

    @Test
    fun outOfRangeBatterySampleIsARequestLevel400WithoutTheRowCode() {
        val enrollment = beginEnrollment("Battery constructor validation")
        acknowledgeUsageFixture(enrollment)
        assertEquals(200, devicePost(enrollment, StudyApi.BATTERY_PATH, "[${battery(50)}]").statusCode())

        assertRequestRejection(devicePost(enrollment, StudyApi.BATTERY_PATH, "[${battery(101)}]"))
        assertRequestRejection(devicePost(enrollment, StudyApi.BATTERY_PATH, "[${battery(50)},${battery(101)}]"))
    }

    @Test
    fun invalidUploadDiagnosticEventIsARequestLevel400WithoutTheRowCode() {
        val enrollment = beginEnrollment("Diagnostic constructor validation")
        acknowledgeUsageFixture(enrollment)
        assertEquals(200, devicePost(enrollment, StudyApi.UPLOAD_DIAGNOSTICS_PATH, diagnostic(1)).statusCode())

        assertRequestRejection(devicePost(enrollment, StudyApi.UPLOAD_DIAGNOSTICS_PATH, diagnostic(0)))
    }
}

class EnrollmentAcknowledgmentReplayContractTest : ChronicleServerTests() {
    @Test
    fun enrollmentAckAcceptsExactReplayAndRejectsChangedAppVersionWithAnySequence() {
        val enrollment = beginEnrollment("Enrollment acknowledgment across app update")
        val build79 = enrollment.acknowledgment.copy(appVersion = "2026.10.23 (79)", decisionSeq = 1L)
        val body = mapper.writeValueAsString(build79)
        assertEquals(200, devicePost(enrollment, StudyApi.COLLECTION_ACK_PATH, body).statusCode())
        assertEquals(200, devicePost(enrollment, StudyApi.COLLECTION_ACK_PATH, body).statusCode())

        for (sequence in listOf(1L, 2L, null)) {
            val updated = build79.copy(appVersion = "2026.10.30 (80)", decisionSeq = sequence)
            assertAckRejected(devicePost(enrollment, StudyApi.COLLECTION_ACK_PATH, mapper.writeValueAsString(updated)))
        }
    }
}

class PendingEnrollmentReplyContractTest : ChronicleServerTests() {
    private fun researcherRequest(path: String, method: String = "GET"): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://localhost:$testHttpPort/chronicle/v3/study/$path"))
            .header("Authorization", "Bearer ${activeFixtureToken(testUser1)}")
            .method(method, HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun pendingEnrollments(studyId: UUID): List<String> {
        val response = researcherRequest("$studyId/pending-enrollments")
        assertEquals(response.body(), 200, response.statusCode())
        return mapper.readTree(response.body()).map { it.asText() }
    }

    @Test
    fun acknowledgedEnrollmentIsNotPendingAndCancellingASecondPhoneSparesItsKey() {
        val enrollment = beginEnrollment("Pending enrollment listing and cancellation")
        val studyId = enrollment.replay.studyId
        val participantId = enrollment.replay.participantId
        val sibling = beginUsageEnrollmentFixture(studyId, participantId, TestDataFactory.androidDevice())
        assertEquals(listOf(participantId), pendingEnrollments(studyId))

        // Only an attempt's own acknowledgment ends it: a sibling phone's leaves the other attempt pending (settings frozen).
        acknowledgeUsageFixture(enrollment)
        assertEquals(listOf(participantId), pendingEnrollments(studyId))
        acknowledgeUsageFixture(sibling)
        assertEquals(emptyList<String>(), pendingEnrollments(studyId))

        val second = beginUsageEnrollmentFixture(studyId, participantId, TestDataFactory.androidDevice())
        val keys = testServer.context.getBean(ApiKeyService::class.java)
        assertNotNull(keys.authenticateApiKey(enrollment.replay.proposedApiKey))
        assertNotNull(keys.authenticateApiKey(second.replay.proposedApiKey))
        assertEquals(listOf(participantId), pendingEnrollments(studyId))

        val cancel = researcherRequest("$studyId/participant/$participantId/pending-enrollment/cancel", "POST")
        assertEquals(cancel.body(), 204, cancel.statusCode())
        assertEquals(emptyList<String>(), pendingEnrollments(studyId))
        assertNotNull(keys.authenticateApiKey(enrollment.replay.proposedApiKey))
        assertNull(keys.authenticateApiKey(second.replay.proposedApiKey))
    }
}

/** A collection acknowledgment is refused with the ack-rejected code exactly when the acknowledgment itself is unacceptable. */
class CollectionAckRejectionContractTest : ChronicleServerTests() {
    private fun postAck(enrollment: UsageEnrollmentFixture, body: String) =
        devicePost(enrollment, StudyApi.COLLECTION_ACK_PATH, body)

    @Test
    fun enrollmentAckWhoseEvidenceContradictsTheReceiptCarriesTheAckCode() {
        val enrollment = beginEnrollment("Ack evidence contradicts the receipt")
        val ack = enrollment.acknowledgment.copy(decisionSeq = 1L)
        val enabled = ack.acknowledgedModules
        listOf(
            ack.copy(manifestDigest = "b".repeat(64)),
            ack.copy(disclosureVersion = "not-the-enrolled-disclosure"),
            ack.copy(acknowledgedModules = enabled.drop(1).toSet()),
            ack.copy(settingsVersion = null),
            ack.copy(disclosureVersion = null, manifestDigest = null),
            ack.copy(settingsVersion = checkNotNull(ack.settingsVersion) - 1),
        ).forEach { assertAckRejected(postAck(enrollment, mapper.writeValueAsString(it))) }
        assertEquals(200, postAck(enrollment, mapper.writeValueAsString(ack)).statusCode())
    }

    @Test
    fun ackAheadOfTheServerSettingsStaysQueuedAfterRestoreOrReplicaLag() {
        val enrollment = beginEnrollment("Ack cites an unissued settings version")
        val ack = enrollment.acknowledgment.copy(decisionSeq = 1L)
        assertEquals(200, postAck(enrollment, mapper.writeValueAsString(ack)).statusCode())

        val unissued = ack.copy(trigger = ConsentTrigger.PARTICIPANT_TOGGLE, decisionSeq = 2L,
            settingsVersion = checkNotNull(ack.settingsVersion) + 50)
        assertAckKept(postAck(enrollment, mapper.writeValueAsString(unissued)))
    }

    @Test
    fun laterDecisionForAModuleTheRevisionNeverEnabledCarriesTheAckCode() {
        val enrollment = beginEnrollment("Ack decides a module the revision never enabled")
        val ack = enrollment.acknowledgment.copy(decisionSeq = 1L)
        assertEquals(200, postAck(enrollment, mapper.writeValueAsString(ack)).statusCode())

        val notEnabled = ack.copy(trigger = ConsentTrigger.PARTICIPANT_TOGGLE, decisionSeq = 2L,
            acknowledgedModules = setOf(CollectionModuleId.BATTERY_TELEMETRY))
        assertAckRejected(postAck(enrollment, mapper.writeValueAsString(notEnabled)))
    }

    @Test
    fun requestLevelAckFailuresCarryNoCode() {
        val enrollment = beginEnrollment("Request-level ack failures")
        val body = mapper.valueToTree<ObjectNode>(enrollment.acknowledgment.copy(decisionSeq = 1L))
        val module = enrollment.acknowledgment.acknowledgedModules.first().id
        val invalidBodies = listOf(
            body.deepCopy().apply { putArray("acknowledgedModules").add("module_from_a_newer_app") },
            body.deepCopy().apply { putArray("acknowledgedModules") },
            body.deepCopy().apply { putArray("declinedModules").add(module) },
            body.deepCopy().apply {
                putArray("acknowledgedModules").add(CollectionModuleId.SENSOR_ACCELEROMETER.id)
                putArray("unavailableModules").add(CollectionModuleId.SENSOR_ACCELEROMETER.id)
            },
            body.deepCopy().apply {
                putArray("declinedModules").add(CollectionModuleId.SENSOR_ACCELEROMETER.id)
                putArray("unavailableModules").add(CollectionModuleId.SENSOR_ACCELEROMETER.id)
            },
            body.deepCopy().apply { putArray("unavailableModules").add(module) },
            body.deepCopy().put("appVersion", "x".repeat(65)),
            body.deepCopy().put("decisionSeq", 0),
            body.deepCopy().put("settingsVersion", 0),
            body.deepCopy().put("disclosureVersion", " "),
            body.deepCopy().put("manifestDigest", "invalid"),
            body.deepCopy().putNull("disclosureVersion"),
            body.deepCopy().putNull("manifestDigest"),
            body.deepCopy().put("trigger", "NEW_TRIGGER"),
            body.deepCopy().putNull("acknowledgedAt"),
        )
        (listOf("{") + invalidBodies.map(mapper::writeValueAsString))
            .forEach { assertAckKept(postAck(enrollment, it)) }
        assertEquals(200, postAck(enrollment, mapper.writeValueAsString(body)).statusCode())
    }
}

/**
 * A device write that fails part-way leaves no rows and no last-upload change and asks the
 * phone to retry; the identical retry then stores each row once. The availability report is one full device snapshot,
 * stamped with the database's receipt time, never the phone's.
 */
class DeviceUploadWriteFailureContractTest : ChronicleServerTests() {
    private companion object {
        const val INSTALL_FAULT = """
            CREATE TABLE IF NOT EXISTS fixture_device_write_fault (study_id uuid NOT NULL, target text NOT NULL,
                marker_column text NOT NULL, marker text NOT NULL, sqlstate text NOT NULL, PRIMARY KEY (study_id, target));
            GRANT SELECT ON fixture_device_write_fault TO chronicle_app;
            CREATE OR REPLACE FUNCTION fixture_device_write_fault() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER AS ${'$'}fault${'$'}
            DECLARE fault fixture_device_write_fault%ROWTYPE;
            BEGIN
              SELECT * INTO fault FROM fixture_device_write_fault WHERE study_id = NEW.study_id AND target = TG_TABLE_NAME;
              IF FOUND AND to_jsonb(NEW) ->> fault.marker_column = fault.marker THEN
                IF fault.sqlstate = 'KILL' THEN PERFORM pg_terminate_backend(pg_backend_pid()); END IF;
                RAISE EXCEPTION 'Fixture device write refused' USING ERRCODE = fault.sqlstate;
              END IF;
              RETURN NEW;
            END ${'$'}fault${'$'};
            CREATE OR REPLACE TRIGGER fixture_device_write_fault BEFORE INSERT OR UPDATE ON battery_telemetry
                FOR EACH ROW EXECUTE FUNCTION fixture_device_write_fault();
            CREATE OR REPLACE TRIGGER fixture_device_write_fault BEFORE INSERT OR UPDATE ON upload_diagnostics
                FOR EACH ROW EXECUTE FUNCTION fixture_device_write_fault();
            CREATE OR REPLACE TRIGGER fixture_device_write_fault BEFORE INSERT OR UPDATE ON participant_stats
                FOR EACH ROW EXECUTE FUNCTION fixture_device_write_fault();
        """
        const val ARM_FAULT = "INSERT INTO fixture_device_write_fault VALUES (?, ?, ?, ?, ?) " +
            "ON CONFLICT (study_id, target) DO UPDATE SET marker_column = EXCLUDED.marker_column, marker = EXCLUDED.marker, " +
            "sqlstate = EXCLUDED.sqlstate"
        const val DISARM_FAULT = "DELETE FROM fixture_device_write_fault WHERE study_id = ?"
        /** Later test classes share the schema: the triggers leave with the fault they serve. */
        const val REMOVE_FAULT = """
            DROP TRIGGER IF EXISTS fixture_device_write_fault ON battery_telemetry;
            DROP TRIGGER IF EXISTS fixture_device_write_fault ON upload_diagnostics;
            DROP TRIGGER IF EXISTS fixture_device_write_fault ON participant_stats;
        """
        /** Disk full, a lost connection and a session killed by the server: each must read as "retry later". */
        val retryableFailures = listOf("53100", "08006", "KILL")
    }

    private class Device(val enrollment: UsageEnrollmentFixture) {
        val studyId: UUID get() = enrollment.replay.studyId
        val participantId: String get() = enrollment.replay.participantId
    }

    private fun enrolledDevice(title: String): Device {
        val studyId = clientUser1.studyApi.createStudy(Study(title = title, contact = "tests@openlattice.com"))
        prepareUsageFixture(clientUser1, studyId)
        val settings = clientUser1.studyApi.getStudySetting(studyId, StudySettingType.DataCollection) as AndroidDataCollectionSetting
        clientUser1.studyApi.updateStudySettings(studyId, StudySettingType.DataCollection, settings.copy(modules = settings.modules +
            setOf(CollectionModuleId.BATTERY_TELEMETRY, CollectionModuleId.SENSOR_AVAILABILITY)
                .associateWith { CollectionDefaults.moduleSetting(it, enabled = true) }))
        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        clientUser1.studyApi.registerParticipant(studyId, participant)
        val enrollment = beginUsageEnrollmentFixture(studyId, participant.participantId, TestDataFactory.androidDevice())
        acknowledgeUsageFixture(enrollment)
        return Device(enrollment)
    }

    private fun batteryJson(id: UUID, levelPercent: Int) = """
        {"id":"$id","timestamp":"2026-10-29T12:00:00Z","timezone":"America/Santiago","levelPercent":$levelPercent,
         "chargingState":"CHARGING","plugType":"AC","temperatureDeciC":250,"voltageMillivolts":4000,"health":"GOOD"}
    """.trimIndent()

    private fun diagnosticJson(id: UUID, count: Int) = """
        {"id":"$id","day":"2026-10-29","moduleFamily":"BATTERY","issueCode":"SAMPLE_QUARANTINED","count":$count,
         "firstOccurredAt":"2026-10-29T12:00:00Z","lastOccurredAt":"2026-10-29T12:00:00Z"}
    """.trimIndent()

    private fun <T> withFault(device: Device, target: String, column: String, marker: String, sqlstate: String, body: () -> T): T {
        hds.connection.use { connection ->
            connection.createStatement().use { it.execute(INSTALL_FAULT) }
            connection.prepareStatement(ARM_FAULT).use {
                it.setObject(1, device.studyId); it.setString(2, target); it.setString(3, column); it.setString(4, marker)
                it.setString(5, sqlstate)
                it.executeUpdate()
            }
        }
        try {
            return body()
        } finally {
            hds.connection.use { connection ->
                connection.prepareStatement(DISARM_FAULT).use { it.setObject(1, device.studyId); it.executeUpdate() }
                connection.createStatement().use { it.execute(REMOVE_FAULT) }
            }
        }
    }

    private fun assertRetryLater(response: HttpResponse<String>, failure: String) {
        assertEquals("$failure: ${response.body()}", 503, response.statusCode())
        assertTrue("$failure asks the phone to retry later", response.headers().firstValue("Retry-After").isPresent)
        assertFalse(response.body(), response.body().contains(UploadLimits.UPLOAD_ROW_REJECTED_CODE))
    }

    private fun ids(sql: String, device: Device): List<String> = hds.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, device.studyId)
            statement.setString(2, device.participantId)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    private fun batteryIds(device: Device) =
        ids("SELECT sample_id::text FROM battery_telemetry WHERE study_id = ? AND participant_id = ?", device)

    private fun diagnosticIds(device: Device) =
        ids("SELECT event_id FROM upload_diagnostics WHERE study_id = ? AND participant_id = ?", device)

    private fun lastUpload(device: Device): List<String> =
        ids("SELECT android_last_ping::text FROM participant_stats WHERE study_id = ? AND participant_id = ?", device)

    private fun databaseNow(): OffsetDateTime = hds.connection.use(com.openlattice.chronicle.UsageEnrollmentFixtures::databaseNow)

    @Test
    fun aBatteryBatchFailingOnALaterRowStoresNothingAndItsRetryStoresEachSampleOnce() {
        for (failure in retryableFailures) {
            val device = enrolledDevice("Battery write failure $failure")
            val samples = listOf(50, 51, 13).map { UUID.randomUUID() to it }
            val body = samples.joinToString(",", "[", "]") { (id, level) -> batteryJson(id, level) }
            val before = lastUpload(device)
            withFault(device, "battery_telemetry", "level_percent", "13", failure) {
                assertRetryLater(devicePost(device.enrollment, StudyApi.BATTERY_PATH, body), failure)
            }
            assertEquals(failure, emptyList<String>(), batteryIds(device))
            assertEquals(failure, before, lastUpload(device))
            repeat(2) { assertEquals(failure, 200, devicePost(device.enrollment, StudyApi.BATTERY_PATH, body).statusCode()) }
            assertEquals(failure, samples.map { it.first.toString() }.sorted(), batteryIds(device).sorted())
            assertEquals(failure, 1, lastUpload(device).size)
        }
    }

    @Test
    fun aDiagnosticsBatchFailingOnALaterRowStoresNothingAndItsRetryStoresEachEventOnce() {
        for (failure in retryableFailures) {
            val device = enrolledDevice("Diagnostics write failure $failure")
            val events = listOf(1, 13).map { UUID.randomUUID() to it }
            val body = events.joinToString(",", "[", "]") { (id, count) -> diagnosticJson(id, count) }
            withFault(device, "upload_diagnostics", "occurrence_count", "13", failure) {
                assertRetryLater(devicePost(device.enrollment, StudyApi.UPLOAD_DIAGNOSTICS_PATH, body), failure)
            }
            assertEquals(failure, emptyList<String>(), diagnosticIds(device))
            repeat(2) { assertEquals(failure, 200, devicePost(device.enrollment, StudyApi.UPLOAD_DIAGNOSTICS_PATH, body).statusCode()) }
            assertEquals(failure, events.map { it.first.toString() }.sorted(), diagnosticIds(device).sorted())
        }
    }

    @Test
    fun anAvailabilityReportWhoseStatisticsFailKeepsThePreviousSnapshot() {
        for (failure in retryableFailures) {
            val device = enrolledDevice("Availability write failure $failure")
            val first = AndroidDeviceSensorAvailability(availableSensors = setOf(AndroidSensorType.light), screenWidthPixels = 720)
            assertEquals(200, devicePost(device.enrollment, AVAILABILITY, mapper.writeValueAsString(first)).statusCode())
            val snapshot = clientUser1.studyApi.getStudySensorAvailability(device.studyId)
            val stats = lastUpload(device)
            val second = first.copy(availableSensors = AndroidSensorType.entries.toSet(), screenWidthPixels = 1080)
            withFault(device, "participant_stats", "participant_id", device.participantId, failure) {
                assertRetryLater(devicePost(device.enrollment, AVAILABILITY, mapper.writeValueAsString(second)), failure)
            }
            assertEquals(failure, snapshot, clientUser1.studyApi.getStudySensorAvailability(device.studyId))
            assertEquals(failure, stats, lastUpload(device))
            assertEquals(failure, 200, devicePost(device.enrollment, AVAILABILITY, mapper.writeValueAsString(second)).statusCode())
            assertEquals(failure, second.availableSensors,
                clientUser1.studyApi.getStudySensorAvailability(device.studyId).single().availableSensors)
        }
    }

    /**
     * A complete report replaces the device's one row field by field; the phone's own `reportedAt`, old or
     * future, is ignored for the database receipt time; a paused participant or a disabled module writes nothing.
     */
    @Test
    fun aFullAvailabilitySnapshotReplacesTheDeviceRowAtTheServersReceiptTime() {
        val device = enrolledDevice("Availability snapshot")
        fun stored() = clientUser1.studyApi.getStudySensorAvailability(device.studyId).single()
        fun report(availability: AndroidDeviceSensorAvailability): Pair<AndroidDeviceSensorAvailability, ClosedRange<Instant>> {
            val before = databaseNow()
            val response = devicePost(device.enrollment, AVAILABILITY, mapper.writeValueAsString(availability))
            assertEquals(response.body(), 200, response.statusCode())
            return stored() to before.toInstant()..databaseNow().toInstant()
        }
        val full = AndroidDeviceSensorAvailability(availableSensors = AndroidSensorType.entries.toSet(), screenWidthPixels = 1080,
            screenHeightPixels = 2400, screenDensityDpi = 420, displayRotation = 1,
            interactionPointerCaptureCapability = InteractionPointerCaptureCapability.PLATFORM_API_UNAVAILABLE,
            reportedAt = OffsetDateTime.parse("2001-01-01T00:00:00Z"))
        val replacement = AndroidDeviceSensorAvailability(availableSensors = setOf(AndroidSensorType.accelerometer),
            screenWidthPixels = 720, screenHeightPixels = 1600, screenDensityDpi = 320, displayRotation = 0,
            interactionPointerCaptureCapability = InteractionPointerCaptureCapability.REQUIRES_INPUT_INTERCEPTION,
            reportedAt = OffsetDateTime.parse("2099-01-01T00:00:00Z"))
        val deviceId = com.openlattice.chronicle.UsageEnrollmentFixtures.enrolledDeviceId(device.enrollment)
        for (sent in listOf(full, replacement)) {
            val (row, window) = report(sent)
            assertEquals(sent.copy(participantId = device.participantId, randomDeviceId = deviceId, reportedAt = null),
                row.copy(reportedAt = null))
            assertTrue("receipt ${row.reportedAt} within $window", checkNotNull(row.reportedAt).toInstant() in window)
        }
        val snapshot = stored()
        val stats = lastUpload(device)
        clientUser1.studyApi.updateParticipationStatusAsRead(device.studyId, device.participantId, ParticipationStatus.PAUSED)
        assertEquals(403, devicePost(device.enrollment, AVAILABILITY, mapper.writeValueAsString(full)).statusCode())
        clientUser1.studyApi.updateParticipationStatusAsRead(device.studyId, device.participantId, ParticipationStatus.ENROLLED)
        val settings = clientUser1.studyApi.getStudySetting(device.studyId, StudySettingType.DataCollection) as AndroidDataCollectionSetting
        clientUser1.studyApi.updateStudySettings(device.studyId, StudySettingType.DataCollection, settings.copy(
            modules = settings.modules + (CollectionModuleId.SENSOR_AVAILABILITY to
                settings.modules.getValue(CollectionModuleId.SENSOR_AVAILABILITY).copy(enabled = false))))
        assertEquals(403, devicePost(device.enrollment, AVAILABILITY, mapper.writeValueAsString(full)).statusCode())
        assertEquals(snapshot, stored())
        assertEquals(stats, lastUpload(device))
    }
}

private val AVAILABILITY = StudyApi.SENSORS_PATH + StudyApi.AVAILABILITY_PATH
