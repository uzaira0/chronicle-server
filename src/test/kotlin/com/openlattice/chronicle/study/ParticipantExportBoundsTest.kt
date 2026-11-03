package com.openlattice.chronicle.study

import com.openlattice.chronicle.pilotExportColumns
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.parseExportCsv
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.UsageEnrollmentFixtures.enrollUsageFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.prepareUsageFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.storedUsageTimestamps
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.client.ChronicleClient
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryHealth
import com.openlattice.chronicle.collection.BatteryPlugType
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.controllers.AuthTokenController
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.generated.BrowserDownloadContracts
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.util.tests.TestDataFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert
import org.junit.Test

/** Participant downloads select rows by their own instant: independently optional, half-open bounds over GET and browser POST. */
class ParticipantExportBoundsTest : ChronicleServerTests() {
    private val chronicleClient: ChronicleClient = clientUser1
    private val santiago = ZoneId.of("America/Santiago")

    private val collectionDatasets = mapOf(
        ParticipantDataType.BatteryTelemetry to ("sample_id" to "sample_timestamp"),
        ParticipantDataType.SensorAvailability to ("random_device_id" to "reported_at"),
    )

    @Test
    fun synchronousExportBoundsAreIndependentlyOptionalAcrossGetAndBrowserPost() {
        val api = chronicleClient.studyApi
        val study = api.createStudy(Study(title = "Optional export bounds", contact = "test@openlattice.com"))
        prepareUsageFixture(chronicleClient, study)
        val times = listOf("2026-09-05T23:59:00-04:00", "2026-09-06T01:00:00-03:00", "2026-09-07T00:00:00-03:00")
            .map(OffsetDateTime::parse)
        val participants = List(2) { uploadedExportParticipant(study, times) }
        val ranges = listOf(null to null, times[1] to null, null to times[2], times[1] to times[2], times[2] to times[1], times[1] to times[1])
        val cases = listOf(participants.take(1), participants).flatMap { scope ->
            ranges.flatMap { range -> listOf("csv", "json").flatMap { format -> listOf("GET", "POST").map { method ->
                ExportBoundsCase(scope.map { it.first }, range.first, range.second, format, method)
            } } }
        }
        cases.forEach { case ->
            val response = downloadParticipantData(study, case)
            val label = "${case.method} ${case.format} ${case.start} ${case.end}"
            val invalid = case.start != null && case.end != null && !case.end.isAfter(case.start)
            Assert.assertEquals("$label: ${response.body()}", if (invalid) 400 else 200, response.statusCode())
            if (!invalid) {
                val expected = participants.filter { it.first in case.scope }.flatMap { (_, events) -> events }
                    .filter { (case.start == null || !it.timestamp.isBefore(case.start)) &&
                        (case.end == null || it.timestamp.isBefore(case.end)) }
                    .associate { it.clientEventId.toString() to it.participantId }
                val rows = exportRows(case.format, response.body())
                Assert.assertEquals(label, expected.size, rows.size)
                Assert.assertEquals(label, expected, rows.associate { it.getValue("client_event_id") to it.getValue("participant_id") })
            }
        }
    }

    private data class ExportBoundsCase(
        val scope: List<String>, val start: OffsetDateTime?, val end: OffsetDateTime?, val format: String, val method: String,
        val dataType: ParticipantDataType = ParticipantDataType.UsageEvents,
    )

    private fun uploadedExportParticipant(study: UUID, times: List<OffsetDateTime>): Pair<String, List<ChronicleUsageEvent>> {
        val api = chronicleClient.studyApi
        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        api.registerParticipant(study, participant)
        val device = TestDataFactory.androidDevice()
        val mobile = enrollUsageFixture(study, participant.participantId, device).mobileApi
        val template = TestDataFactory.chronicleUsageEvents(study, participant.participantId, 1).single() as ChronicleUsageEvent
        val events = times.map { time -> template.copy(timestamp = time, timezone = "America/Santiago", clientEventId = UUID.randomUUID()) }
        Assert.assertEquals(events.size, mobile.uploadAndroidUsageEventDataV4(
            study, participant.participantId, device.randomDeviceId, ChronicleData(events)).acceptedIds.size)
        RLSRequestContext.withSystemContext {
            testServer.context.getBean(AppDataUploadService::class.java).drainEventStorage()
        }
        Assert.assertEquals(times.map { it.toInstant() }.toSet(), storedUsageTimestamps(study, participant.participantId))
        return participant.participantId to events
    }

    private fun downloadParticipantData(study: UUID, case: ExportBoundsCase): HttpResponse<String> {
        val token = activeFixtureToken(testUser1)
        val csrf = "fixture-csrf"
        val parameters = mutableListOf(StudyApi.DATA_TYPE to case.dataType.name, StudyApi.RESPONSE_TYPE to case.format)
        parameters += case.scope.map { StudyApi.PARTICIPANT_ID to it }
        case.start?.let { parameters += StudyApi.START_DATE to it.toString() }
        case.end?.let { parameters += StudyApi.END_DATE to it.toString() }
        if (case.method == "POST") parameters += BrowserDownloadContracts.BROWSER_DOWNLOAD_CSRF_FIELD to csrf
        val encoded = parameters.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}" }
        val path = "http://localhost:$testHttpPort${StudyApi.BASE}/$study/participants/data"
        val request = if (case.method == "GET") HttpRequest.newBuilder(URI("$path?$encoded"))
            .header("Authorization", "Bearer $token").GET()
        else HttpRequest.newBuilder(URI(path)).header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", "${AuthTokenController.AUTH_COOKIE_NAME}=$token; ${AuthTokenController.CSRF_COOKIE_NAME}=$csrf")
            .POST(HttpRequest.BodyPublishers.ofString(encoded))
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    /**
     * Battery and availability exports select rows by their own instant with the same half-open bounds as
     * usage. Rows a millisecond before, at and after each bound, at Santiago's two clock changes, for two participants:
     * every bound mode, CSV and JSON, GET and browser POST returns exactly the rows inside, in time order, battery
     * instants in their row's zone; a range with no rows still carries the CSV header.
     */
    @Test
    fun pilotCollectionExportsHonorEveryBoundMode() {
        val api = chronicleClient.studyApi
        val study = api.createStudy(Study(title = "Collection export bounds", contact = "test@openlattice.com"))
        prepareUsageFixture(chronicleClient, study)
        val settings = api.getStudySetting(study, StudySettingType.DataCollection) as AndroidDataCollectionSetting
        api.updateStudySettings(study, StudySettingType.DataCollection, settings.copy(modules = settings.modules +
            (CollectionModuleId.BATTERY_TELEMETRY to CollectionDefaults.moduleSetting(CollectionModuleId.BATTERY_TELEMETRY, enabled = true))))
        val fallBack = checkNotNull(santiago.rules.nextTransition(Instant.parse("2026-03-01T00:00:00Z"))).instant
        val springForward = checkNotNull(santiago.rules.nextTransition(fallBack)).instant
        val instants = listOf(fallBack, springForward).flatMap { listOf(it.minusMillis(1), it, it.plusMillis(1)) }
        val seeded = List(2) { seedCollectionRows(study, instants) }
        val start = fallBack.atZone(santiago).toOffsetDateTime()
        val end = springForward.atZone(santiago).toOffsetDateTime()
        val ranges = listOf(null to null, start to null, null to end, start to end, end.plusDays(1) to end.plusDays(2))
        val variants = listOf("csv", "json").flatMap { format -> listOf("GET", "POST").map { method -> format to method } }
        val selections = collectionDatasets.keys.flatMap { type ->
            listOf(seeded.take(1), seeded).flatMap { scope -> ranges.map { range -> Triple(type, scope, range) } }
        }
        for ((type, scope, range) in selections) {
            val inside = instants.filter { (range.first == null || it >= range.first!!.toInstant()) &&
                (range.second == null || it < range.second!!.toInstant()) }
            val expected = scope.flatMap { rows -> inside.map { rows.idsByInstant(type).getValue(it) } }
            variants.forEach { (format, method) ->
                assertCollectionExport(study,
                    ExportBoundsCase(scope.map { it.participantId }, range.first, range.second, format, method, type), expected)
            }
        }
    }

    /** One participant's rows of each collection dataset, keyed by the instant they were captured or reported. */
    private data class SeededCollection(
        val participantId: String, val batteryIds: Map<Instant, String>, val availabilityDevices: Map<Instant, String>,
    ) {
        fun idsByInstant(type: ParticipantDataType) =
            if (type == ParticipantDataType.BatteryTelemetry) batteryIds else availabilityDevices
    }

    /** Battery samples through the phone API and one availability snapshot per instant (a device each) through SQL. */
    private fun seedCollectionRows(study: UUID, instants: List<Instant>): SeededCollection {
        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        chronicleClient.studyApi.registerParticipant(study, participant)
        val device = TestDataFactory.androidDevice()
        val mobile = enrollUsageFixture(study, participant.participantId, device).mobileApi
        val samples = instants.associateWith { instant ->
            BatterySample(UUID.randomUUID().toString(), instant.atZone(santiago).toOffsetDateTime(), "America/Santiago", 50,
                BatteryChargingState.DISCHARGING, BatteryPlugType.UNPLUGGED, 250, 4000, BatteryHealth.GOOD)
        }
        mobile.uploadBatteryTelemetryV4(study, participant.participantId, device.randomDeviceId, samples.values.toList())
        val devices = instants.associateWith { instant -> insertAvailability(study, participant.participantId, instant) }
        return SeededCollection(participant.participantId, samples.mapValues { it.value.id }, devices.mapValues { it.value.toString() })
    }

    private fun insertAvailability(study: UUID, participantId: String, reportedAt: Instant): UUID = UUID.randomUUID().also { deviceId ->
        hds.connection.use { connection ->
            connection.prepareStatement("""
                INSERT INTO android_device_sensor_availability (study_id, participant_id, random_device_id, available_sensors, reported_at)
                VALUES (?, ?, ?, '{light}', ?)
            """.trimIndent()).use { statement ->
                statement.setObject(1, study)
                statement.setString(2, participantId)
                statement.setObject(3, deviceId)
                statement.setObject(4, reportedAt.atOffset(ZoneOffset.UTC))
                statement.executeUpdate()
            }
        }
    }

    /** Exactly the [expected] rows, in time order, battery instants in their row's zone, and the documented columns. */
    private fun assertCollectionExport(study: UUID, case: ExportBoundsCase, expected: List<String>) {
        val (idColumn, timeColumn) = collectionDatasets.getValue(case.dataType)
        val columns = pilotExportColumns.getValue(case.dataType)
        val label = "${case.dataType} ${case.method} ${case.format} ${case.start} ${case.end} ${case.scope}"
        val response = downloadParticipantData(study, case)
        Assert.assertEquals("$label: ${response.body()}", 200, response.statusCode())
        val rows = exportRows(case.format, response.body())
        Assert.assertEquals(label, expected.sorted(), rows.map { it.getValue(idColumn) }.sorted())
        val times = rows.map { OffsetDateTime.parse(it.getValue(timeColumn)) }
        Assert.assertEquals("$label in time order", times.map { it.toInstant() }.sorted(), times.map { it.toInstant() })
        if (case.dataType == ParticipantDataType.BatteryTelemetry) times.forEach {
            Assert.assertEquals("$label offset of $it", santiago.rules.getOffset(it.toInstant()), it.offset)
        }
        if (case.format == "csv") {
            Assert.assertEquals("$label header", columns, response.body().lineSequence().first().split(',').toSet())
        } else rows.forEach { Assert.assertEquals(label, columns, it.keys) }
    }

    private fun exportRows(format: String, body: String): List<Map<String, String>> = if (format == "json") {
        ObjectMappers.getJsonMapper().readTree(body).map { row ->
            row.fields().asSequence().associate { (key, value) -> key to (if (value.isNull) "" else value.asText()) }
        }
    } else {
        parseExportCsv(body)
    }

}
