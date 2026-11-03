package com.openlattice.chronicle.e2e.scenarios

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.UsageEnrollmentFixtures
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.generated.ChronicleHeaders
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.study.ParticipantDataType
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.util.tests.TestDataFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.io.path.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bytes the real Android client sends, captured by its unit tests, replayed into this server and read back through
 * the researcher export: EnrollmentWireContractTest (CHRONICLE_ANDROID_ENROLL_DIR), PilotUploadFixtureTest
 * (CHRONICLE_ANDROID_PILOT_UPLOAD_DIR) and IdentityLabelUploadJourneyTest (CHRONICLE_ANDROID_IDENTITY_DIR). Run only by
 * the `androidReplayTest` task (scripts/local-ci.sh android-replay); each test fails when its directory is unset or
 * empty. Captured bytes are sent as they are, except values only this server mints: the study id and the enrollment
 * capture's participant id in the path, the invitation code and manifest digest headers, and the acknowledgment's
 * manifestDigest and settingsVersion. The captured disclosure version is seeded as the study's, not rewritten.
 */
class AndroidReplayTest : ChronicleServerTests() {
    private val mapper = ObjectMappers.getJsonMapper()
    private val http = HttpClient.newHttpClient()
    private val researcher = clientUser1

    /** The values the Android tests put in, which must come back out of the export. */
    private companion object {
        val PILOT_MODULES = setOf(CollectionModuleId.USAGE_EVENTS, CollectionModuleId.IN_APP_ACTIVITY_CLASS,
            CollectionModuleId.DEVICE_LIFECYCLE, CollectionModuleId.USER_IDENTIFICATION, CollectionModuleId.UPLOAD_TELEMETRY,
            CollectionModuleId.SENSOR_AVAILABILITY, CollectionModuleId.BATTERY_TELEMETRY)
        const val PILOT_PARTICIPANT = "pilot-p-07"
        const val PILOT_DEVICE = "device-7"
        const val ENROLL_DEVICE = "00000000-0000-4000-8000-000000000007"
        const val PROPOSED_KEY = "ck_AAAAAAAA_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val DISCLOSURE_VERSION = "consent-1"
        val PILOT_USAGE = mapOf(
            "00000000-0000-0000-0000-0000000000a1" to (mapOf("app_package_name" to "cl.banco.app", "application_label" to "Banco",
                "activity_class" to "cl.banco.app.Login", "username" to "Study participant", "timezone" to "America/Santiago",
                "interaction_type" to "Activity Resumed", "event_type" to "1") to Instant.parse("2026-10-30T12:15:00Z")),
            "00000000-0000-0000-0000-0000000000a2" to (mapOf("app_package_name" to "com.whatsapp", "application_label" to "WhatsApp",
                "activity_class" to "", "username" to "Someone else", "timezone" to "America/Santiago",
                "interaction_type" to "Activity Paused", "event_type" to "2") to Instant.parse("2026-10-31T00:40:00Z")),
        )
        const val PILOT_BATTERY_ID = "00000000-0000-0000-0000-0000000000b1"
        val PILOT_BATTERY = mapOf("timezone" to "America/Santiago", "level_percent" to "37", "charging_state" to "DISCHARGING",
            "plug_type" to "UNPLUGGED", "temperature_deci_c" to "291", "voltage_millivolts" to "3812", "health" to "GOOD")
        val PILOT_BATTERY_AT: Instant = Instant.parse("2026-10-30T15:00:00Z")
        val PILOT_SENSORS = setOf("accelerometer", "light")
        val IDENTITY_LABELS = mapOf("example.participant" to "Study participant", "example.other" to "Someone else",
            "example.back" to "Study participant", "example.unanswered" to "")
        val ROUTES = mapOf("android" to StudyApi.ANDROID_PATH, "battery" to StudyApi.ANDROID_PATH + StudyApi.BATTERY_PATH,
            "availability" to StudyApi.ANDROID_PATH + StudyApi.SENSORS_PATH + StudyApi.AVAILABILITY_PATH)
        val CAPTURED_PATH = Regex("^${StudyApi.V4_BASE}/[^/]+${StudyApi.PARTICIPANT_PATH}/[^/]+(/.+)$")
        /** Set by the HTTP client from the connection; the replay does not choose them. */
        val TRANSPORT_HEADERS = setOf("host", "connection", "content-length", "accept-encoding")
    }

    /** Each regular file of the producer's directory by name without `.json`; fails when unset, missing or empty. */
    private fun captures(variable: String): Map<String, ByteArray> {
        val directory = Path.of(checkNotNull(System.getenv(variable)) { "$variable must name the Android test output" })
        val files = Files.list(directory).use { paths -> paths.filter(Files::isRegularFile).toList() }
        check(files.isNotEmpty()) { "$variable ($directory) holds no Android captures" }
        return files.sortedBy { it.name }.associate { it.name.removeSuffix(".json") to Files.readAllBytes(it) }
    }

    private fun study(enabled: Set<CollectionModuleId>, disclosureVersion: String? = null): UUID {
        val studyId = researcher.studyApi.createStudy(Study(title = "android replay", contact = "test@example.org"))
        UsageEnrollmentFixtures.prepareUsageFixture(researcher, studyId)
        val current = researcher.studyApi.getStudySetting(studyId, StudySettingType.DataCollection) as AndroidDataCollectionSetting
        researcher.studyApi.updateStudySettings(studyId, StudySettingType.DataCollection, current.copy(
            modules = current.modules.mapValues { (id, setting) ->
                setting.copy(enabled = id in enabled, required = id == CollectionModuleId.USAGE_EVENTS)
            },
        ))
        if (disclosureVersion != null) researcher.studyApi.updateStudySettings(studyId, StudySettingType.ParticipantPolicy,
            TestDataFactory.participantPolicy().copy(version = disclosureVersion))
        return studyId
    }

    private fun register(studyId: UUID, participantId: String = TestDataFactory.participant().participantId): String {
        researcher.studyApi.registerParticipant(studyId, TestDataFactory.participant().copy(participantId = participantId))
        return participantId
    }

    private fun participantPath(studyId: UUID, participantId: String, suffix: String) =
        "${StudyApi.V4_BASE}/$studyId${StudyApi.PARTICIPANT_PATH}/$participantId$suffix"

    private fun post(path: String, headers: Map<String, String>, body: ByteArray): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://localhost:$testHttpPort$path")).apply { headers.forEach { (name, value) -> header(name, value) } }
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun capturedHeader(request: JsonNode, name: String): String =
        request.path("headers").properties().single { it.key.equals(name, ignoreCase = true) }.value.asText()

    /** The captured headers as sent, with each of [minted] (which the capture must carry) set to this server's value. */
    private fun replayHeaders(request: JsonNode, minted: Map<String, String> = emptyMap()): Map<String, String> {
        val captured = request.path("headers").properties().associate { it.key to it.value.asText() }
        minted.keys.forEach { name -> check(captured.keys.any { it.equals(name, true) }) { "the capture lacks $name" } }
        return captured.filterKeys { it.lowercase() !in TRANSPORT_HEADERS }.mapValues { (name, value) ->
            minted.entries.firstOrNull { it.key.equals(name, true) }?.value ?: value
        }
    }

    private fun replayPath(request: JsonNode, studyId: UUID, participantId: String, expectedSuffix: String): String {
        assertEquals("POST", request.path("method").asText())
        val path = request.path("path").asText()
        val suffix = checkNotNull(CAPTURED_PATH.matchEntire(path)) { "not a participant route: $path" }.groupValues[1]
        assertEquals(expectedSuffix, suffix)
        return participantPath(studyId, participantId, suffix)
    }

    private fun flush() = RLSRequestContext.withSystemContext {
        testServer.context.getBean(AppDataUploadService::class.java).drainEventStorage()
    }

    private fun export(studyId: UUID, type: ParticipantDataType, participantId: String, from: Instant, to: Instant) =
        researcher.testStudyApi.getParticipantsData(studyId, type, setOf(participantId),
            from.atOffset(java.time.ZoneOffset.UTC), to.atOffset(java.time.ZoneOffset.UTC), "json")
            .map { row -> row.mapValues { it.value?.toString().orEmpty() } }

    /** Posts each usage body to the usage route; the receipt must accept every clientEventId it carries. */
    private fun ingestUsage(studyId: UUID, participantId: String, headers: Map<String, String>, bodies: Collection<ByteArray>) {
        for (body in bodies) {
            val response = post(participantPath(studyId, participantId, StudyApi.ANDROID_PATH), headers, body)
            assertEquals(response.body(), 200, response.statusCode())
            assertReceipt("usage", mapper.readTree(body).map { it.path("clientEventId").asText() }.toSet(), response.body())
        }
    }

    private fun assertReceipt(label: String, sent: Set<String>, body: String) {
        val receipt = mapper.readTree(body)
        assertEquals("$label accepted ids", sent, receipt.path("acceptedIds").map(JsonNode::asText).toSet())
        assertEquals("$label discarded ids", emptySet<String>(), receipt.path("discardedIds").map(JsonNode::asText).toSet())
    }

    private fun mobileHeaders(enrolled: UsageEnrollmentFixtures.UsageEnrollmentFixture) = mapOf(
        "Content-Type" to "application/json; charset=UTF-8",
        ChronicleHeaders.API_KEY_HEADER to enrolled.replay.proposedApiKey,
        ChronicleHeaders.RANDOM_DEVICE_ID_HEADER to enrolled.replay.device.randomDeviceId,
    )

    @Test
    fun capturedEnrollmentAndItsAcknowledgmentAreAccepted() {
        val captured = captures("CHRONICLE_ANDROID_ENROLL_DIR").mapValues { mapper.readTree(it.value) }
        assertEquals(setOf("enroll-request", "collection-ack-request"), captured.keys)
        val enroll = captured.getValue("enroll-request")
        val ack = captured.getValue("collection-ack-request")
        val ackBody = mapper.readTree(ack.path("body").asText()) as ObjectNode
        val acknowledged = setOf(CollectionModuleId.USAGE_EVENTS, CollectionModuleId.BATTERY_TELEMETRY,
            CollectionModuleId.UPLOAD_TELEMETRY)
        val declined = setOf(CollectionModuleId.IN_APP_ACTIVITY_CLASS)
        val studyId = study(acknowledged + declined, DISCLOSURE_VERSION)
        // The capture's participant id is not one this server issues; a registered one takes its path segment.
        val participantId = register(studyId)
        val (invitation, preview) = UsageEnrollmentFixtures.newUsageEnrollment(studyId, participantId, TestDataFactory.androidDevice())

        // Device metadata is whatever the build reports (Robolectric here): the server must store what the phone sent.
        val expectedDevice = mapper.readTree(enroll.path("body").asText()) as ObjectNode
        assertEquals("com.openlattice.chronicle.sources.AndroidDevice", expectedDevice.path("@class").asText())
        assertEquals(ENROLL_DEVICE, expectedDevice.path("randomDeviceId").asText())
        assertEquals(ENROLL_DEVICE, capturedHeader(enroll, ChronicleHeaders.RANDOM_DEVICE_ID_HEADER))
        assertEquals("00000000-0000-4000-8000-000000000008",
            capturedHeader(enroll, ChronicleHeaders.ENROLLMENT_ATTEMPT_ID_HEADER))
        assertEquals(PROPOSED_KEY, capturedHeader(enroll, ChronicleHeaders.PROPOSED_API_KEY_HEADER))
        val enrolled = post(replayPath(enroll, studyId, participantId, "/enroll"), replayHeaders(enroll, mapOf(
            ChronicleHeaders.ENROLLMENT_CODE_HEADER to invitation.enrollmentCode,
            ChronicleHeaders.MANIFEST_DIGEST_HEADER to invitation.manifestDigest,
        )), enroll.path("body").asText().toByteArray())
        assertEquals(enrolled.body(), 200, enrolled.statusCode())
        assertEquals("the issued key is the proposed one", PROPOSED_KEY,
            mapper.readTree(enrolled.body()).path("apiKey").asText())
        val device = researcher.studyApi.getStudyDevices(studyId).getValue(participantId).single()
        expectedDevice.remove(listOf("randomDeviceId", "fcmRegistrationToken"))
        assertEquals(expectedDevice, mapper.valueToTree<JsonNode>(device.getValue("sourceDevice")))

        assertEquals(ENROLL_DEVICE, capturedHeader(ack, ChronicleHeaders.RANDOM_DEVICE_ID_HEADER))
        assertEquals(PROPOSED_KEY, capturedHeader(ack, ChronicleHeaders.API_KEY_HEADER))
        assertTrue("the first acknowledgment has no assigned decision sequence", ackBody.path("decisionSeq").isNull)
        if (ackBody.hasNonNull("manifestDigest")) ackBody.put("manifestDigest", invitation.manifestDigest)
        if (ackBody.hasNonNull("settingsVersion")) ackBody.put("settingsVersion", preview.manifest.settingsVersion)
        val reported = post(replayPath(ack, studyId, participantId, "/android/collection-ack"), replayHeaders(ack),
            mapper.writeValueAsBytes(ackBody))
        assertEquals(reported.body(), 200, reported.statusCode())
        val stored = researcher.studyApi.getStudyCollectionAcknowledgments(studyId).single { it.participantId == participantId }
        assertEquals(acknowledged, stored.acknowledgedModules)
        assertEquals(declined, stored.declinedModules)
        assertEquals(emptySet<CollectionModuleId>(), stored.unavailableModules)
        assertEquals(ENROLL_DEVICE, stored.sourceRandomDeviceId)
        assertEquals(ConsentTrigger.ENROLLMENT, stored.trigger)
        assertEquals(Instant.parse("2026-10-30T15:00:00Z"), stored.acknowledgedAt.toInstant())
        assertEquals("the app version the phone reported", ackBody.path("appVersion").asText(), stored.appVersion)
        assertTrue(!stored.appVersion.isNullOrBlank())
        assertEquals(DISCLOSURE_VERSION, stored.disclosureVersion)
        assertEquals(invitation.manifestDigest, stored.manifestDigest)
        assertEquals(preview.manifest.settingsVersion, stored.settingsVersion)
        assertEquals(1L, stored.decisionSeq)
    }

    @Test
    fun capturedPilotUploadsExportEveryValue() {
        val bodies = captures("CHRONICLE_ANDROID_PILOT_UPLOAD_DIR")
        assertEquals("every captured route is replayed", ROUTES.keys, bodies.keys)
        val studyId = study(PILOT_MODULES)
        val participantId = register(studyId, PILOT_PARTICIPANT)
        val enrolled = UsageEnrollmentFixtures.enrollUsageFixture(studyId, participantId,
            TestDataFactory.androidDevice().copy(randomDeviceId = PILOT_DEVICE))
        for ((route, body) in bodies) {
            val response = post(participantPath(studyId, participantId, ROUTES.getValue(route)), mobileHeaders(enrolled), body)
            assertEquals("$route ${response.body()}", 200, response.statusCode())
            when (route) {
                "android" -> assertReceipt(route, mapper.readTree(body).map { it.path("clientEventId").asText() }.toSet(), response.body())
                "battery" -> assertReceipt(route, mapper.readTree(body).map { it.path("id").asText() }.toSet(), response.body())
            }
        }
        flush()
        val from = Instant.parse("2026-10-29T00:00:00Z")
        val to = maxOf(Instant.parse("2026-11-01T00:00:00Z"), Instant.now().plusSeconds(86_400))

        val usage = export(studyId, ParticipantDataType.UsageEvents, participantId, from, to).associateBy { it["client_event_id"] }
        assertEquals(PILOT_USAGE.keys, usage.keys)
        for ((id, expected) in PILOT_USAGE) {
            val row = usage.getValue(id)
            assertEquals(id, expected.first, row.filterKeys { it in expected.first.keys })
            assertEquals(id, expected.second, OffsetDateTime.parse(row["event_timestamp"]).toInstant())
        }
        val battery = export(studyId, ParticipantDataType.BatteryTelemetry, participantId, from, to).single()
        assertEquals(PILOT_BATTERY_ID, battery["sample_id"])
        assertEquals(PILOT_BATTERY, battery.filterKeys { it in PILOT_BATTERY.keys })
        assertEquals(PILOT_BATTERY_AT, OffsetDateTime.parse(battery["sample_timestamp"]).toInstant())
        val sensors = export(studyId, ParticipantDataType.SensorAvailability, participantId, from, to).single()
        assertEquals(PILOT_SENSORS, sensors.getValue("available_sensors").removeSurrounding("{", "}").split(',').toSet())
        val display = mapOf("screen_width_pixels" to "", "screen_height_pixels" to "", "screen_density_dpi" to "",
            "display_rotation" to "", "interaction_pointer_capture_capability" to "")
        assertEquals(display, sensors.filterKeys { it in display })
    }

    @Test
    fun capturedIdentityLabelsExportPerPackage() {
        val captured = captures("CHRONICLE_ANDROID_IDENTITY_DIR")
        // The instants the journey fed to the usage poller, written beside the bodies: independent of the bytes replayed.
        val fed = mapper.readTree(checkNotNull(captured["expected-times"]) { "the journey's fed instants" }).properties()
            .associate { it.key to Instant.parse(it.value.asText()) }
        assertEquals(IDENTITY_LABELS.keys, fed.keys)
        val bodies = (captured - "expected-times").values
        val rows = bodies.flatMap { mapper.readTree(it).toList() }
        val studyId = study(PILOT_MODULES)
        // The journey uses AuditStores' fixed participant; this server mints the study id.
        val participantId = register(studyId, "participant-001")
        val enrolled = UsageEnrollmentFixtures.enrollUsageFixture(studyId, participantId, TestDataFactory.androidDevice())
        ingestUsage(studyId, participantId, mobileHeaders(enrolled), bodies)
        flush()
        val times = rows.map { OffsetDateTime.parse(it.path("timestamp").asText()).toInstant() }
        val exported = export(studyId, ParticipantDataType.UsageEvents, participantId,
            times.min().minusSeconds(86_400), times.max().plusSeconds(86_400))
        assertEquals("one exported row per labelled package", IDENTITY_LABELS.size, exported.size)
        assertEquals(IDENTITY_LABELS, exported.associate { it.getValue("app_package_name") to it.getValue("username") })
        assertEquals("each package exported at the instant the journey fed it", fed,
            exported.associate { it.getValue("app_package_name") to OffsetDateTime.parse(it.getValue("event_timestamp")).toInstant() })
        // Client event ids are minted on the phone (random): the export keeps exactly the uploaded ones.
        assertEquals(rows.map { it.path("clientEventId").asText() }.toSet(), exported.map { it.getValue("client_event_id") }.toSet())
        for (row in exported) {
            val packageName = row.getValue("app_package_name")
            assertEquals(packageName, row["application_label"])
            assertEquals("$packageName.Main", row["activity_class"])
            assertEquals("Activity Resumed", row["interaction_type"])
            assertEquals("1", row["event_type"])
            assertEquals("America/Santiago", row["timezone"])
        }
    }
}
