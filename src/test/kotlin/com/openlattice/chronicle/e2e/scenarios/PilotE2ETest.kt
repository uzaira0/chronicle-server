package com.openlattice.chronicle.e2e.scenarios

import com.openlattice.chronicle.pilotExportColumns
import com.openlattice.chronicle.UsageEnrollmentFixtures.UPLOAD_ROW_CAP
import com.openlattice.chronicle.client.updateParticipationStatusAsRead
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.parseExportCsv
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.UsageEnrollmentFixtures
import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryHealth
import com.openlattice.chronicle.collection.BatteryPlugType
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionCadence
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.e2e.dsl.chronicleScenario
import com.openlattice.chronicle.e2e.dsl.di.ProvidersBundle
import com.openlattice.chronicle.e2e.dsl.scopes.StudyScope
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.generated.ChronicleHeaders
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.study.ParticipantDataType
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.util.tests.TestDataFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.OffsetDateTime
import java.time.Duration
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PilotE2ETest : ChronicleServerTests() {
    private val providers = ProvidersBundle.fromSpringContext(testServer.context)
    private val mapper = ObjectMappers.getJsonMapper()
    private val http = HttpClient.newHttpClient()
    private val modules = setOf(CollectionModuleId.USAGE_EVENTS, CollectionModuleId.IN_APP_ACTIVITY_CLASS,
        CollectionModuleId.DEVICE_LIFECYCLE, CollectionModuleId.USER_IDENTIFICATION,
        CollectionModuleId.UPLOAD_TELEMETRY, CollectionModuleId.SENSOR_AVAILABILITY, CollectionModuleId.BATTERY_TELEMETRY)
    private val dataTypes = setOf(ParticipantDataType.UsageEvents, ParticipantDataType.BatteryTelemetry,
        ParticipantDataType.SensorAvailability, ParticipantDataType.UploadDiagnostics)

    @Test
    fun pilotUploadsRetryIdempotentlyAndExportEveryModuleInBothFormats() = chronicleScenario(providers) {
        asUser("test_admin") {
            study(providers.data.study("PilotExports")) {
                configurePilot(allRequired = false)
                val subject = TestDataFactory.participant()
                client.studyApi.registerParticipant(studyId, subject)
                val device = TestDataFactory.androidDevice()
                val enrolled = UsageEnrollmentFixtures.enrollUsageFixture(studyId, subject.participantId, device)
                val captured = OffsetDateTime.parse("2026-04-04T23:30:00-03:00")
                val event = usageEvent(studyId, subject.participantId, captured)
                val usage = ChronicleData(listOf(event, event.copy(timestamp = captured.plusMinutes(60), user = "Other",
                    clientEventId = UUID.randomUUID())))
                repeat(2) { enrolled.mobileApi.uploadAndroidUsageEventDataV4(studyId, subject.participantId, device.randomDeviceId, usage) }
                flush()
                val battery = batterySample(captured)
                val diagnostic = diagnostic(captured).copy(count = 2)
                repeat(2) {
                    enrolled.mobileApi.uploadBatteryTelemetryV4(studyId, subject.participantId, device.randomDeviceId, listOf(battery))
                    mobilePost(enrolled, StudyApi.UPLOAD_DIAGNOSTICS_PATH, listOf(diagnostic))
                    mobilePost(enrolled, StudyApi.SENSORS_PATH + StudyApi.AVAILABILITY_PATH, AndroidDeviceSensorAvailability())
                }
                assertEquals(1, client.studyApi.getStudySensorAvailability(studyId).size)
                assertExports(subject.participantId, captured, usage)
            }
        }
    }

    private fun StudyScope.configurePilot(allRequired: Boolean) {
        val current = client.studyApi.getStudySetting(studyId, StudySettingType.DataCollection) as AndroidDataCollectionSetting
        client.studyApi.updateStudySettings(studyId, StudySettingType.DataCollection, current.copy(
            modules = current.modules.mapValues { (id, setting) ->
                setting.copy(enabled = id in modules,
                    required = id in modules && id.consentGated && (allRequired || id == CollectionModuleId.USAGE_EVENTS))
            },
        ))
        val saved = client.studyApi.getStudySetting(studyId, StudySettingType.DataCollection) as AndroidDataCollectionSetting
        assertEquals(modules, saved.effectiveEnabledModuleIds())
        assertEquals(if (allRequired) modules.filterTo(mutableSetOf()) { it.consentGated } else setOf(CollectionModuleId.USAGE_EVENTS),
            saved.modules.filter { (id, setting) -> id in modules && setting.required }.keys)
    }

    private fun StudyScope.assertExports(participant: String, captured: OffsetDateTime, usage: ChronicleData) {
        for (type in dataTypes) {
            val start = if (type == ParticipantDataType.SensorAvailability) OffsetDateTime.now().minusDays(1) else captured.minusDays(1)
            val end = if (type == ParticipantDataType.SensorAvailability) OffsetDateTime.now().plusDays(1) else captured.plusDays(1)
            val rows = client.testStudyApi.getParticipantsData(studyId, type, setOf(participant), start, end, "json")
            assertEquals(type.name, if (type == ParticipantDataType.UsageEvents) 2 else 1, rows.size)
            assertCsvRows(type, setOf(participant), start, end, rows.size)
            if (type == ParticipantDataType.UsageEvents) {
                assertEquals(setOf("Target Child", "Other"), rows.map { it["username"] }.toSet())
                assertTrue(rows.any { it["activity_class"] == (usage.first() as ChronicleUsageEvent).activityClass })
                assertEquals(1, client.testStudyApi.getParticipantsData(studyId, type, setOf(participant),
                    captured, captured.plusMinutes(60), "json").size)
            }
        }
    }

    @Test
    fun twentyFiveDevicesThirtyOneDaysExportAcrossFourHundredDaysWithoutTruncation() = chronicleScenario(providers) {
        asUser("test_admin") {
            study(providers.data.study("PilotMonth")) {
                configurePilot(allRequired = false)
                val start = OffsetDateTime.now().minusDays(31).withNano(0)
                val end = start.plusDays(31)
                val exportEnd = end.plusDays(1)
                val exportStart = exportEnd.minusDays(400)
                val cadence = CollectionCadence.DEFAULT_COLLECTION.intervalSeconds
                val samples = (Duration.between(start, end).seconds / cadence).toInt()
                val participants = (1..25).map { index ->
                    val subject = TestDataFactory.participant().copy(participantId = "pilot-$index")
                    client.studyApi.registerParticipant(studyId, subject)
                    val device = TestDataFactory.androidDevice()
                    val enrolled = UsageEnrollmentFixtures.enrollUsageFixture(studyId, subject.participantId, device)
                    val events = (0 until samples).map { n -> usageEvent(studyId, subject.participantId, start.plusSeconds(n * cadence)) }
                    val lifecycle = events.map(::lifecycleEvent)
                    enrolled.mobileApi.uploadAndroidUsageEventDataV4(studyId, subject.participantId,
                        device.randomDeviceId, ChronicleData(events + lifecycle))
                    flush()
                    enrolled.mobileApi.uploadBatteryTelemetryV4(studyId, subject.participantId, device.randomDeviceId,
                        events.map { batterySample(it.timestamp) })
                    mobilePost(enrolled, StudyApi.UPLOAD_DIAGNOSTICS_PATH, (0 until 31).map { day -> diagnostic(start.plusDays(day.toLong())) })
                    mobilePost(enrolled, StudyApi.SENSORS_PATH + StudyApi.AVAILABILITY_PATH, AndroidDeviceSensorAvailability())
                    subject.participantId
                }.toSet() + (26..101).map { index ->
                    // Past the retired 25-per-study enrollment cap and the 100-participant export ceiling.
                    val subject = TestDataFactory.participant().copy(participantId = "pilot-$index")
                    client.studyApi.registerParticipant(studyId, subject)
                    if (index == 26) UsageEnrollmentFixtures.enrollUsageFixture(studyId, subject.participantId, TestDataFactory.androidDevice())
                    subject.participantId
                }
                val usageCount = 25 * samples * 2
                assertEquals(usageCount, client.testStudyApi.getParticipantsData(studyId, ParticipantDataType.UsageEvents,
                    participants, exportStart, exportEnd, "json").size)
                // Past the retired 50,000-row JSON budget.
                assertTrue(usageCount > 50_000)
                // The one export streams every row of the whole study once, per data type, as CSV.
                val expected = mapOf(ParticipantDataType.UsageEvents to usageCount, ParticipantDataType.BatteryTelemetry to 25 * samples,
                    ParticipantDataType.UploadDiagnostics to 25 * 31, ParticipantDataType.SensorAvailability to 25)
                for ((type, count) in expected) {
                    assertCsvRows(type, participants, exportStart, exportEnd, count, streamed = type != ParticipantDataType.SensorAvailability)
                }
            }
        }
    }

    private fun StudyScope.assertCsvRows(
        type: ParticipantDataType, participants: Set<String>, start: OffsetDateTime, end: OffsetDateTime, count: Int,
        streamed: Boolean = false,
    ) {
        val query = listOf("dataType" to type.name, "startDate" to start.toString(), "endDate" to end.toString(),
            "responseType" to "csv") + participants.map { "participantId" to it }
        val encoded = query.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}" }
        val response = http.send(HttpRequest.newBuilder(
            URI("http://localhost:$testHttpPort${StudyApi.BASE}/$studyId/participants/data?$encoded"))
            .header("Authorization", "Bearer ${activeFixtureToken(adminUser)}").GET().build(), HttpResponse.BodyHandlers.ofLines())
        assertEquals(type.name, 200, response.statusCode())
        // A body larger than the server's output buffer streams from the cursor: no length is known up front.
        if (streamed) assertTrue(type.name, response.headers().firstValue("Content-Length").isEmpty)
        val rows = parseExportCsv(response.body().use { lines -> lines.toList().joinToString("\n") })
        assertEquals(type.name, count, rows.size)
        assertEquals("${type.name} rows arrive once", count, rows.toSet().size)
    }

    @Test
    fun allRequiredPilotExposesServerPauseResumeAndNotEnrolledState() = chronicleScenario(providers) {
        asUser("test_admin") {
            study(providers.data.study("PilotState")) {
                configurePilot(allRequired = true)
                val subject = TestDataFactory.participant()
                client.studyApi.registerParticipant(studyId, subject)
                val device = TestDataFactory.androidDevice()
                val enrolled = UsageEnrollmentFixtures.enrollUsageFixture(studyId, subject.participantId, device)
                for (status in listOf(ParticipationStatus.PAUSED, ParticipationStatus.ENROLLED, ParticipationStatus.NOT_ENROLLED)) {
                    client.studyApi.updateParticipationStatusAsRead(studyId, subject.participantId, status)
                    assertEquals(status, client.studyApi.getStudyParticipants(studyId).single().participationStatus)
                    val response = mobileRequest(enrolled, StudyApi.STATUS_PATH)
                    assertEquals(response.body(), 200, response.statusCode())
                    assertEquals(status.name, mapper.readTree(response.body()).path("participationStatus").asText())
                }
            }
        }
    }

    /**
     * Diagnostics are not research data, so they never refresh the dashboard's last upload. A code from a
     * newer app is stored as UNRECOGNIZED_ISSUE instead of refusing the batch.
     */
    @Test
    fun diagnosticsLeaveLastUploadAloneAndAnUnknownCodeIsStoredAsUnrecognized() = chronicleScenario(providers) {
        asUser("test_admin") {
            study(providers.data.study("PilotDiagnostics")) {
                configurePilot(allRequired = false)
                val subject = TestDataFactory.participant()
                client.studyApi.registerParticipant(studyId, subject)
                val device = TestDataFactory.androidDevice()
                val enrolled = UsageEnrollmentFixtures.enrollUsageFixture(studyId, subject.participantId, device)
                fun lastUpload() = client.studyApi.getParticipantStats(studyId)[subject.participantId]?.androidLastPing
                assertEquals("enrollment is not an upload", null, lastUpload())
                val captured = OffsetDateTime.now().minusHours(1).withNano(0)
                val newer = mapper.convertValue(diagnostic(captured), Map::class.java) +
                    mapOf("id" to UUID.randomUUID().toString(), "issueCode" to "A_CODE_FROM_A_NEWER_APP")
                mobilePost(enrolled, StudyApi.UPLOAD_DIAGNOSTICS_PATH, listOf(diagnostic(captured), newer))
                assertEquals(null, lastUpload())
                val rows = client.testStudyApi.getParticipantsData(studyId, ParticipantDataType.UploadDiagnostics,
                    setOf(subject.participantId), captured.minusDays(1), captured.plusDays(1), "json")
                assertEquals(listOf("CONNECTION_FAILURE", "UNRECOGNIZED_ISSUE"), rows.map { it["issue_code"] }.sortedBy(Any?::toString))
                // Last upload is the server's receipt of committed research rows, never a capture time, and only moves forward.
                val beforeAvailability = OffsetDateTime.now().minusSeconds(1)
                mobilePost(enrolled, StudyApi.SENSORS_PATH + StudyApi.AVAILABILITY_PATH, AndroidDeviceSensorAvailability())
                val afterAvailability = checkNotNull(lastUpload()) { "an availability report is an upload" }
                assertTrue("$afterAvailability is the availability receipt", !afterAvailability.isBefore(beforeAvailability))
                val beforeBattery = OffsetDateTime.now().minusSeconds(1)
                enrolled.mobileApi.uploadBatteryTelemetryV4(studyId, subject.participantId, device.randomDeviceId,
                    listOf(batterySample(captured.minusDays(3))))
                val afterBattery = checkNotNull(lastUpload())
                assertTrue("$afterBattery is the receipt of a delayed old capture", !afterBattery.isBefore(beforeBattery))
                assertTrue(!afterBattery.isBefore(afterAvailability))
                // The phone's enrollment and uploads persist no IP address: their audit rows carry the withheld marker.
                val phoneIpRefs = hds.connection.use { connection ->
                    connection.prepareStatement("SELECT client_ip_ref FROM audit WHERE study_id = ? AND request_path LIKE ?").use {
                        it.setString(1, studyId.toString()); it.setString(2, "${StudyApi.V4_BASE}/%")
                        it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                    }
                }
                assertTrue("the phone's requests were audited", phoneIpRefs.isNotEmpty())
                assertEquals(setOf(com.openlattice.chronicle.util.ClientIpRecord.WITHHELD), phoneIpRefs.toSet())
            }
        }
    }

    /** One seeded participant: every pilot dataset's row, with labels a CSV writer must quote or defuse. */
    private data class Seeded(
        val participant: String,
        val usage: ChronicleUsageEvent,
        val lifecycle: ChronicleUsageEvent,
        val battery: BatterySample,
        val diagnostic: AndroidUploadDiagnosticEvent,
        val availability: AndroidDeviceSensorAvailability,
        val deviceId: String,
        val receipts: Map<String, OffsetDateTime>,
    )

    private fun StudyScope.seed(participantId: String, tag: String, captured: OffsetDateTime, level: Int): Seeded {
        val subject = TestDataFactory.participant().copy(participantId = participantId)
        client.studyApi.registerParticipant(studyId, subject)
        val device = TestDataFactory.androidDevice()
        val enrolled = UsageEnrollmentFixtures.enrollUsageFixture(studyId, participantId, device)
        val usage = usageEvent(studyId, participantId, captured).copy(appPackageName = "org.example.$tag",
            applicationLabel = "=HYPERLINK(\"x\"), $tag \"quoted\"\nsecond line", user = "Child, \"$tag\"",
            activityClass = "org.example.$tag.Main", clientEventId = UUID.randomUUID())
        val lifecycle = lifecycleEvent(usage).copy(timestamp = captured.plusMinutes(1))
        assertEquals(setOf(usage.clientEventId, lifecycle.clientEventId), enrolled.mobileApi.uploadAndroidUsageEventDataV4(
            studyId, participantId, device.randomDeviceId, ChronicleData(listOf(usage, lifecycle))).acceptedIds)
        flush()
        val battery = batterySample(captured.plusMinutes(2)).copy(levelPercent = level)
        enrolled.mobileApi.uploadBatteryTelemetryV4(studyId, participantId, device.randomDeviceId, listOf(battery))
        val diagnostic = diagnostic(captured).copy(count = level)
        mobilePost(enrolled, StudyApi.UPLOAD_DIAGNOSTICS_PATH, listOf(diagnostic))
        val availability = AndroidDeviceSensorAvailability(availableSensors = setOf(
            com.openlattice.chronicle.android.AndroidSensorType.light, com.openlattice.chronicle.android.AndroidSensorType.accelerometer),
            screenWidthPixels = 1000 + level, screenHeightPixels = 2000 + level, screenDensityDpi = 400, displayRotation = 1)
        mobilePost(enrolled, StudyApi.SENSORS_PATH + StudyApi.AVAILABILITY_PATH, availability)
        return Seeded(participantId, usage, lifecycle, battery, diagnostic, availability,
            UsageEnrollmentFixtures.enrolledDeviceId(enrolled), UsageEnrollmentFixtures.storedReceiptTimes(studyId, participantId))
    }

    private fun StudyScope.download(type: ParticipantDataType, participants: Set<String>, start: OffsetDateTime,
                                    end: OffsetDateTime, format: String): String {
        val query = listOf("dataType" to type.name, "startDate" to start.toString(), "endDate" to end.toString(),
            "responseType" to format) + participants.map { "participantId" to it }
        val encoded = query.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}" }
        val response = http.send(HttpRequest.newBuilder(
            URI("http://localhost:$testHttpPort${StudyApi.BASE}/$studyId/participants/data?$encoded"))
            .header("Authorization", "Bearer ${activeFixtureToken(adminUser)}").GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(response.body(), 200, response.statusCode())
        return response.body()
    }

    /**
     * Every exported record keeps its own identity and values. Two participants of one study and a
     * participant with the same ID in another study upload every pilot dataset at the two instants Santiago's autumn
     * clock change repeats. Each dataset, as CSV and JSON, for one participant and for both, carries exactly the
     * documented columns, each row's exact values with the offset of its zone at that instant, and no foreign row.
     */
    @Test
    fun pilotExportsPreserveEveryRecordField() = chronicleScenario(providers) {
        asUser("test_admin") {
            val santiago = java.time.ZoneId.of("America/Santiago")
            val fallBack = checkNotNull(santiago.rules.nextTransition(java.time.Instant.parse("2026-03-01T00:00:00Z"))).instant
            // The same Santiago wall time, 23:30, before and after the clocks fall back.
            val first = fallBack.minusSeconds(1800).atZone(santiago).toOffsetDateTime()
            val second = fallBack.plusSeconds(1800).atZone(santiago).toOffsetDateTime()
            check(first.toLocalTime() == second.toLocalTime() && first.offset != second.offset)
            study(providers.data.study("PilotForeign")) {
                configurePilot(allRequired = false)
                seed("pilot-export-a", "foreign", first, 11)
            }
            study(providers.data.study("PilotFields")) {
                configurePilot(allRequired = false)
                val seeded = listOf(seed("pilot-export-a", "alpha", first, 21), seed("pilot-export-b", "beta", second, 37))
                val start = first.minusDays(1)
                val end = second.plusDays(1)
                val now = OffsetDateTime.now()
                for (scope in listOf(seeded.take(1), seeded)) {
                    val participants = scope.mapTo(linkedSetOf()) { it.participant }
                    for (format in listOf("json", "csv")) {
                        val label = "$format for $participants"
                        for ((type, columns) in pilotExportColumns) {
                            val (from, to) = if (type == ParticipantDataType.SensorAvailability) now.minusDays(1) to now.plusDays(1)
                                else start to end
                            val body = download(type, participants, from, to, format)
                            val rows: List<Map<String, String>> = if (format == "json") {
                                val raw = mapper.readValue(body,
                                    object : com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Any?>>>() {})
                                // PILOT-DATA: dedicated datasets and diagnostics write a missing scalar as "", never null.
                                if (type != ParticipantDataType.UsageEvents) {
                                    assertEquals("$label ${type.name} JSON nulls", emptyList<String>(),
                                        raw.flatMap { row -> row.filterValues { it == null }.keys })
                                }
                                raw.map { row -> row.mapValues { it.value?.toString().orEmpty() } }
                            } else parseExportCsv(body)
                            rows.forEach { assertEquals("$label ${type.name} columns", columns, it.keys) }
                            assertEquals("$label ${type.name} study", setOf(studyId.toString()), rows.mapTo(hashSetOf()) { it["study_id"] })
                            assertEquals("$label ${type.name} participants", participants,
                                rows.mapTo(linkedSetOf()) { it.getValue("participant_id") })
                            assertExportedValues(type, format, scope, rows, label)
                        }
                    }
                }
                // H-empty-export: a range holding none of their rows still names every documented column once.
                val empty = start.minusYears(1)
                for ((type, columns) in pilotExportColumns) {
                    val body = download(type, seeded.mapTo(linkedSetOf()) { it.participant }, empty, empty.plusDays(1), "csv")
                    val header = body.lineSequence().first().split(',')
                    assertEquals("${type.name} empty CSV header", columns, header.toSet())
                    assertEquals("${type.name} empty CSV header", columns.size, header.size)
                    assertEquals(type.name, emptyList<Map<String, String>>(), parseExportCsv(body))
                }
            }
        }
    }

    /** CSV defuses a value a spreadsheet would run as a formula by prefixing a quote; JSON keeps it as sent. */
    private fun exported(value: String, format: String) =
        if (format == "csv" && value.firstOrNull() in setOf('=', '+', '-', '@')) "'$value" else value

    private fun assertInstant(label: String, expected: OffsetDateTime, actual: String?, zone: java.time.ZoneId? = null) {
        val parsed = OffsetDateTime.parse(checkNotNull(actual) { "$label is missing" })
        assertEquals(label, expected.toInstant(), parsed.toInstant())
        if (zone != null) assertEquals("$label offset", zone.rules.getOffset(parsed.toInstant()), parsed.offset)
    }

    private val santiago: java.time.ZoneId = java.time.ZoneId.of("America/Santiago")

    private fun assertExportedValues(type: ParticipantDataType, format: String, scope: List<Seeded>, rows: List<Map<String, String>>,
                                     label: String) = when (type) {
        ParticipantDataType.UsageEvents -> assertUsageValues(format, scope, rows, label)
        ParticipantDataType.BatteryTelemetry -> assertBatteryValues(scope, rows, label)
        ParticipantDataType.SensorAvailability -> assertAvailabilityValues(scope, rows, label)
        ParticipantDataType.UploadDiagnostics -> assertDiagnosticValues(scope, rows, label)
        else -> error("not a pilot dataset: $type")
    }

    private fun assertUsageValues(format: String, scope: List<Seeded>, rows: List<Map<String, String>>, label: String) {
        val byId = rows.associateBy { it.getValue("client_event_id") }
        assertEquals("$label usage rows", scope.size * 2, rows.size)
        for (seeded in scope) for (event in listOf(seeded.usage, seeded.lifecycle)) {
            val row = checkNotNull(byId[event.clientEventId.toString()]) { "$label lacks ${event.clientEventId}" }
            assertEquals(label, seeded.participant, row["participant_id"])
            assertEquals(label, mapOf("app_package_name" to event.appPackageName,
                "application_label" to exported(event.applicationLabel, format), "activity_class" to event.activityClass.orEmpty(),
                "interaction_type" to event.interactionType, "event_type" to event.eventType.toString(), "timezone" to event.timezone,
                "username" to exported(event.user, format)), row.filterKeys { it in setOf("app_package_name", "application_label",
                "activity_class", "interaction_type", "event_type", "timezone", "username") })
            assertInstant("$label event_timestamp", event.timestamp, row["event_timestamp"], santiago)
            assertInstant("$label uploaded_at", seeded.receipts.getValue(event.clientEventId.toString()), row["uploaded_at"], santiago)
        }
    }

    private fun assertBatteryValues(scope: List<Seeded>, rows: List<Map<String, String>>, label: String) {
        assertEquals("$label battery rows", scope.map { it.battery.id }.toSet(), rows.mapTo(hashSetOf()) { it["sample_id"] })
        assertEquals(label, rows.size, scope.size)
        for (seeded in scope) {
            val sample = seeded.battery
            val row = rows.single { it["sample_id"] == sample.id }
            assertEquals(label, seeded.participant, row["participant_id"])
            assertEquals(label, mapOf("timezone" to sample.timezone, "level_percent" to sample.levelPercent.toString(),
                "charging_state" to sample.chargingState.name, "plug_type" to sample.plugType.name, "health" to sample.health.name,
                "temperature_deci_c" to sample.temperatureDeciC.toString(), "voltage_millivolts" to sample.voltageMillivolts.toString(),
                "low_power_mode" to ""), row.filterKeys { it in setOf("timezone", "level_percent", "charging_state", "plug_type",
                "health", "temperature_deci_c", "voltage_millivolts", "low_power_mode") })
            assertInstant("$label sample_timestamp", sample.timestamp, row["sample_timestamp"], santiago)
            assertInstant("$label uploaded_at", seeded.receipts.getValue(sample.id), row["uploaded_at"], santiago)
        }
    }

    private fun assertAvailabilityValues(scope: List<Seeded>, rows: List<Map<String, String>>, label: String) {
        assertEquals(label, scope.size, rows.size)
        assertEquals("$label one device per participant", scope.size, rows.mapTo(hashSetOf()) { it["random_device_id"] }.size)
        for (seeded in scope) {
            val sent = seeded.availability
            val row = rows.single { it["participant_id"] == seeded.participant }
            assertEquals(label, sent.availableSensors.mapTo(hashSetOf()) { it.name },
                row.getValue("available_sensors").removeSurrounding("{", "}").split(',').toSet())
            assertEquals(label, listOf(sent.screenWidthPixels, sent.screenHeightPixels, sent.screenDensityDpi, sent.displayRotation)
                .map { it.toString() }, listOf("screen_width_pixels", "screen_height_pixels", "screen_density_dpi", "display_rotation")
                .map { row[it] })
            assertEquals(label, "", row["interaction_pointer_capture_capability"])
            assertEquals(label, seeded.deviceId, row["random_device_id"])
            assertInstant("$label reported_at", seeded.receipts.getValue(seeded.deviceId), row["reported_at"])
        }
    }

    private fun assertDiagnosticValues(scope: List<Seeded>, rows: List<Map<String, String>>, label: String) {
        assertEquals(label, scope.size, rows.size)
        for (seeded in scope) {
            val event = seeded.diagnostic
            val row = rows.single { it["event_id"] == event.id }
            assertEquals(label, seeded.participant, row["participant_id"])
            assertEquals(label, mapOf("diagnostic_day" to event.day.toString(), "module_family" to event.moduleFamily,
                "issue_code" to event.issueCode, "occurrence_count" to event.count.toString(), "http_status" to "",
                "error_type" to ""), row.filterKeys { it in setOf("diagnostic_day", "module_family", "issue_code",
                "occurrence_count", "http_status", "error_type") })
            assertEquals(label, seeded.deviceId, row["random_device_id"])
            assertInstant("$label uploaded_at", seeded.receipts.getValue(event.id), row["uploaded_at"])
            assertInstant("$label first_occurred_at", event.firstOccurredAt, row["first_occurred_at"])
            assertInstant("$label last_occurred_at", event.lastOccurredAt, row["last_occurred_at"])
        }
        // A device's diagnostics name the same enrolled app instance as its availability report.
        assertEquals(label, scope.size, rows.mapTo(hashSetOf()) { it["random_device_id"] }.size)
    }

    /**
     * 25 phones return at once with a backlog, uploading in phone-sized batches while the upload-buffer
     * mover runs, and resend some batches as after a lost response. Every acknowledged row is stored once, nothing is
     * left buffered, and the export counts match. A battery batch at the row cap is accepted whole; one row more is
     * refused and changes nothing.
     */
    @Test
    fun twentyFivePhonesUploadTheirBacklogsAtOnceWhileTheMoverRuns() = chronicleScenario(providers) {
        asUser("test_admin") {
            study(providers.data.study("PilotBurst")) {
                configurePilot(allRequired = false)
                val start = OffsetDateTime.now().minusDays(10).withNano(0)
                val cadence = CollectionCadence.DEFAULT_COLLECTION.intervalSeconds
                val samples = (Duration.ofDays(10).seconds / cadence).toInt()
                val phones = (1..25).map { index ->
                    val subject = TestDataFactory.participant().copy(participantId = "burst-$index")
                    client.studyApi.registerParticipant(studyId, subject)
                    val device = TestDataFactory.androidDevice()
                    Triple(subject.participantId, device, UsageEnrollmentFixtures.enrollUsageFixture(studyId, subject.participantId, device))
                }
                val accepted = java.util.concurrent.ConcurrentHashMap.newKeySet<UUID>()
                val stopMover = java.util.concurrent.atomic.AtomicBoolean(false)
                val executor = java.util.concurrent.Executors.newFixedThreadPool(phones.size + 1)
                try {
                    val mover = executor.submit { while (!stopMover.get()) { flush(); Thread.sleep(20) } }
                    phones.map { (participant, device, enrolled) ->
                        executor.submit {
                            val events = (0 until samples).map { n -> usageEvent(studyId, participant, start.plusSeconds(n * cadence)) }
                            (events + events.map(::lifecycleEvent)).chunked(500).forEachIndexed { index, chunk ->
                                val upload = { enrolled.mobileApi.uploadAndroidUsageEventDataV4(studyId, participant,
                                    device.randomDeviceId, ChronicleData(chunk)) }
                                val receipt = upload()
                                assertEquals(chunk.mapTo(hashSetOf()) { it.clientEventId }, receipt.acceptedIds)
                                if (index % 3 == 0) assertEquals("a resent batch gets the same receipt", receipt, upload())
                                accepted += receipt.acceptedIds
                            }
                            events.map { batterySample(it.timestamp) }.chunked(1000).forEach { batch ->
                                repeat(2) {
                                    enrolled.mobileApi.uploadBatteryTelemetryV4(studyId, participant, device.randomDeviceId, batch)
                                }
                            }
                        }
                    }.forEach { it.get(10, java.util.concurrent.TimeUnit.MINUTES) }
                    stopMover.set(true)
                    mover.get(2, java.util.concurrent.TimeUnit.MINUTES)
                } finally {
                    stopMover.set(true)
                    executor.shutdownNow()
                }
                flush()
                assertEquals("nothing is left buffered", 0, rowCount("SELECT count(*) FROM upload_buffer WHERE study_id = ?"))
                assertEquals(25 * samples * 2, accepted.size)
                assertEquals("each acknowledged row is stored once", accepted.map(UUID::toString).toSet(), storedClientEventIds())
                assertEquals(accepted.size, rowCount("SELECT count(*) FROM chronicle_usage_events WHERE study_id::text = ?::text"))
                val participants = phones.mapTo(linkedSetOf()) { it.first }
                val exportStart = start.minusDays(1)
                val exportEnd = OffsetDateTime.now().plusDays(1)
                assertCsvRows(ParticipantDataType.UsageEvents, participants, exportStart, exportEnd, accepted.size, streamed = true)
                assertCsvRows(ParticipantDataType.BatteryTelemetry, participants, exportStart, exportEnd, 25 * samples, streamed = true)
                assertBatteryRowCap(phones.first(), start)
            }
        }
    }

    /** A battery batch at the row cap is accepted whole; one row more is refused and changes nothing. */
    private fun StudyScope.assertBatteryRowCap(
        phone: Triple<String, AndroidDevice, UsageEnrollmentFixtures.UsageEnrollmentFixture>, start: OffsetDateTime,
    ) {
        val (participant, device, enrolled) = phone
        val batteryRows = "SELECT count(*) FROM battery_telemetry WHERE study_id = ?"
        val batteryBefore = rowCount(batteryRows)
        val atCap = (0 until UPLOAD_ROW_CAP).map { batterySample(start.plusSeconds(it.toLong())) }
        assertEquals(UPLOAD_ROW_CAP, enrolled.mobileApi.uploadBatteryTelemetryV4(studyId, participant, device.randomDeviceId,
            atCap).acceptedIds.size)
        assertEquals(batteryBefore + UPLOAD_ROW_CAP, rowCount(batteryRows))
        val overCap = (0..UPLOAD_ROW_CAP).map { batterySample(start.plusSeconds(it.toLong())) }
        val refused = runCatching {
            enrolled.mobileApi.uploadBatteryTelemetryV4(studyId, participant, device.randomDeviceId, overCap)
        }.exceptionOrNull()
        assertTrue("one row over the cap is refused, got $refused",
            refused is com.geekbeast.retrofit.RhizomeRetrofitCallException && refused.code == 400)
        assertEquals(batteryBefore + UPLOAD_ROW_CAP, rowCount(batteryRows))
    }

    private fun StudyScope.rowCount(sql: String): Int = hds.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, studyId)
            statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    private fun StudyScope.storedClientEventIds(): Set<String> = hds.connection.use { connection ->
        connection.createStatement().use { it.execute("SET max_parallel_workers_per_gather=0") }
        connection.prepareStatement("SELECT client_event_id FROM chronicle_usage_events WHERE study_id::text = ?").use { statement ->
            statement.setString(1, studyId.toString())
            statement.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    private fun usageEvent(study: UUID, participant: String, time: OffsetDateTime): ChronicleUsageEvent =
        (TestDataFactory.chronicleUsageEvents(study, participant, 1).single() as ChronicleUsageEvent).copy(
            appPackageName = "org.example.pilot", interactionType = "Activity Resumed",
            eventType = com.openlattice.chronicle.android.ChronicleUsageEventType.ACTIVITY_RESUMED.value,
            timestamp = time, timezone = "America/Santiago", user = "Target Child",
            applicationLabel = "Pilot", activityClass = "org.example.pilot.Main",
        )

    /** A supplemental Device Lifecycle row as the phone sends it: the Android system package, its state, no user. */
    private fun lifecycleEvent(event: ChronicleUsageEvent): ChronicleUsageEvent = event.copy(
        appPackageName = "android", interactionType = "Battery Low", applicationLabel = "Android System",
        eventType = com.openlattice.chronicle.android.ChronicleUsageEventType.BATTERY_LOW.value,
        activityClass = "android.intent.action.BATTERY_LOW", user = "", clientEventId = UUID.randomUUID(),
    )

    private fun batterySample(time: OffsetDateTime) = BatterySample(UUID.randomUUID().toString(), time, "America/Santiago", 73,
        BatteryChargingState.DISCHARGING, BatteryPlugType.UNPLUGGED, 230, 4100, BatteryHealth.GOOD)

    private fun diagnostic(time: OffsetDateTime) = AndroidUploadDiagnosticEvent(UUID.randomUUID().toString(), time.toLocalDate(),
        "USAGE_LIFECYCLE", "CONNECTION_FAILURE", 1, time, time)

    private fun flush() = RLSRequestContext.withSystemContext {
        testServer.context.getBean(AppDataUploadService::class.java).drainEventStorage()
    }

    private fun mobilePost(enrollment: UsageEnrollmentFixtures.UsageEnrollmentFixture, suffix: String, body: Any) {
        val response = mobileRequest(enrollment, StudyApi.ANDROID_PATH + suffix, body)
        assertEquals(response.body(), 200, response.statusCode())
    }

    private fun mobileRequest(enrollment: UsageEnrollmentFixtures.UsageEnrollmentFixture, suffix: String, body: Any? = null): HttpResponse<String> {
        val replay = enrollment.replay
        val request = HttpRequest.newBuilder(URI("http://localhost:$testHttpPort${StudyApi.V4_BASE}" +
            "/${replay.studyId}/participant/${replay.participantId}$suffix"))
            .header("Content-Type", "application/json")
            .header(com.openlattice.chronicle.util.API_KEY_HEADER, replay.proposedApiKey)
            .header(ChronicleHeaders.RANDOM_DEVICE_ID_HEADER, replay.device.randomDeviceId)
            .apply { if (body == null) GET() else POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }
}
