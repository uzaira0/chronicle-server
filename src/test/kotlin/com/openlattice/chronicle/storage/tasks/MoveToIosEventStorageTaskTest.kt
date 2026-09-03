package com.openlattice.chronicle.storage.tasks

import com.openlattice.chronicle.sensorkit.SensorType
import com.openlattice.chronicle.sensorkit.SensorDataSample
import com.openlattice.chronicle.services.upload.ScreenTimeCaptureSource
import com.openlattice.chronicle.services.upload.ScreenTimeConfidence
import com.openlattice.chronicle.services.upload.ScreenTimeUsageEnvelope
import com.openlattice.chronicle.services.upload.ScreenTimeUsageRecord
import com.openlattice.chronicle.services.upload.ScreenTimeUsageRowKind
import com.openlattice.chronicle.services.upload.ScreenTimeUsageUploadService
import com.openlattice.chronicle.storage.PostgresEventColumns
import com.openlattice.chronicle.storage.PostgresEventTables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.util.UUID

class MoveToIosEventStorageTaskTest {
    @Test
    fun duplicateCleanupTreatsRawSensorPayloadAsText() {
        val sql = PostgresEventTables.getDeleteIosSensorDataFromTempTable("duplicate_ios_events_test")

        assertEquals(
            PostgresEventColumns.IOS_SCREEN_TIME_RAW_SOURCE_LABEL,
            PostgresEventColumns.RAW_SENSOR_PAYLOAD,
        )
        assertTrue(
            sql.contains(
                "COALESCE(sensor_data.ios_screen_time_raw_source_label,'') = " +
                    "COALESCE(duplicate_ios_events_test.ios_screen_time_raw_source_label,'')"
            )
        )
        assertTrue(!sql.contains("InvalidParameterException"))
    }

    @Test
    fun directScreenTimeDedupIncludesCaptureAndBucketTimestamps() {
        val buildSql = PostgresEventTables.buildTempTableOfDuplicatesForIos("duplicate_ios_events_test")
        val deleteSql = PostgresEventTables.getDeleteIosSensorDataFromTempTable("duplicate_ios_events_test")

        assertTrue(buildSql.contains("WHERE ios_screen_time_source = 'deviceActivityExport'"))
        assertTrue(buildSql.contains("GROUP BY study_id,participant_id,sensor_type"))
        assertTrue(buildSql.contains("recordeddate"))
        assertTrue(buildSql.contains("datetimestart"))
        assertTrue(buildSql.contains("datetimeend"))
        assertTrue(buildSql.contains("exact_recordeddate"))
        assertTrue(
            deleteSql.contains(
                "COALESCE(sensor_data.exact_recordeddate,to_timestamp(0) AT TIME ZONE 'UTC') = " +
                    "COALESCE(duplicate_ios_events_test.exact_recordeddate,to_timestamp(0) AT TIME ZONE 'UTC')"
            )
        )
    }

    @Test
    fun preservesAccelerometerBatchPayload() {
        val timestamp = OffsetDateTime.parse("2026-07-12T03:10:17Z")
        val payload = """{"schemaVersion":1,"frequencyHz":50.0,"provenance":"system_recorded","samples":[{"offsetSeconds":0.0,"xG":0.1,"yG":0.2,"zG":0.9}]}"""
        val sample = SensorDataSample(
            id = UUID.randomUUID(),
            dateRecorded = timestamp,
            duration = 0.02,
            data = payload,
            device = """{"model":"iPhone16,1","name":"U15","systemName":"iOS","systemVersion":"26.5"}""",
            timezone = "America/Chicago",
            sensor = SensorType.accelerometer,
            startDate = timestamp,
            endDate = timestamp.plusNanos(20_000_000),
        )

        val stored = mapSensorDataToStorage(listOf(sample))
            .getValue(SensorType.accelerometer)
            .single()
            .associate { it.col.name to it.value }

        assertEquals(payload, stored.getValue(PostgresEventColumns.RAW_SENSOR_PAYLOAD.name))
        assertEquals("accelerometer", stored.getValue(PostgresEventColumns.SENSOR_TYPE.name))
        assertEquals(timestamp, stored.getValue(PostgresEventColumns.EXACT_RECORDED_DATE_TIME.name))
    }

    @Test
    fun preservesValidatedCompactAccelerometerPayload() {
        val timestamp = OffsetDateTime.parse("2026-07-12T03:10:17Z")
        val payload = """{"schemaVersion":2,"encoding":"delta-zigzag-varint-zlib-base64","sampleCount":3,"nominalFrequencyHz":50.0,"provenance":"system_recorded","timeUnit":"nanoseconds","uncompressedByteCount":19,"channels":[{"name":"x","unit":"g","scale":0.000244140625},{"name":"y","unit":"g","scale":0.000244140625},{"name":"z","unit":"g","scale":0.000244140625}],"payload":"eNpjaNjSKfyNcTYTK9N3RgbWqQ4MTAA+wQVA"}"""
        val sample = SensorDataSample(
            id = UUID.randomUUID(),
            dateRecorded = timestamp,
            duration = 0.04,
            data = payload,
            device = """{"model":"iPhone16,1","name":"U15","systemName":"iOS","systemVersion":"26.5"}""",
            timezone = "America/Chicago",
            sensor = SensorType.accelerometer,
            startDate = timestamp,
            endDate = timestamp.plusNanos(40_000_123),
        )

        val stored = mapSensorDataToStorage(listOf(sample))
            .getValue(SensorType.accelerometer)
            .single()
            .associate { it.col.name to it.value }

        assertEquals(payload, stored.getValue(PostgresEventColumns.RAW_SENSOR_PAYLOAD.name))
    }

    @Test
    fun preservesValidatedLosslessAccelerometerPayload() {
        val timestamp = OffsetDateTime.parse("2026-07-12T03:10:17Z")
        val payload = """{"schemaVersion":3,"encoding":"ieee754-binary64-xor-bytepack-zlib-base64","sampleCount":3,"nominalFrequencyHz":50.0,"provenance":"system_recorded","timeUnit":"seconds","uncompressedByteCount":82,"channels":[{"name":"x","unit":"g"},{"name":"y","unit":"g"},{"name":"z","unit":"g"}],"payload":"eNpjYIAADvspVQ/d14lUixswsOhPfrLbfudMEJhVyMjxX+vyHdmUaZH7T8KF9uV8b4tOzDpk//4/GIjLQ2im/R8AKY8o6Q=="}"""
        val sample = SensorDataSample(
            id = UUID.randomUUID(),
            dateRecorded = timestamp,
            duration = 0.040000123,
            data = payload,
            device = """{"model":"iPhone16,1","name":"U15","systemName":"iOS","systemVersion":"26.5"}""",
            timezone = "America/Chicago",
            sensor = SensorType.accelerometer,
            startDate = timestamp,
            endDate = timestamp.plusNanos(40_000_123),
        )

        val stored = mapSensorDataToStorage(listOf(sample))
            .getValue(SensorType.accelerometer)
            .single()
            .associate { it.col.name to it.value }

        assertEquals(payload, stored.getValue(PostgresEventColumns.RAW_SENSOR_PAYLOAD.name))
    }

    @Test
    fun preservesValidatedPedometerPayload() {
        val timestamp = OffsetDateTime.parse("2026-07-12T03:10:17Z")
        val payload = """{"schemaVersion":1,"provenance":"os_buffered","numberOfSteps":267,"distanceMeters":183.19096727995202,"floorsAscended":1,"floorsDescended":2,"averageActivePaceSecondsPerMeter":0.8123456789012345,"currentCadenceStepsPerSecond":1.9876543210987654}"""
        val sample = sensorSample(timestamp, payload, SensorType.pedometer, duration = 3600.0)

        val stored = mapSensorDataToStorage(listOf(sample))
            .getValue(SensorType.pedometer)
            .single()
            .associate { it.col.name to it.value }

        assertEquals(payload, stored.getValue(PostgresEventColumns.RAW_SENSOR_PAYLOAD.name))
        assertEquals("pedometer", stored.getValue(PostgresEventColumns.SENSOR_TYPE.name))
    }

    @Test
    fun rejectsInvalidPedometerPayloads() {
        val timestamp = OffsetDateTime.parse("2026-07-12T03:10:17Z")
        val invalidPayloads = listOf(
            """{"schemaVersion":2,"provenance":"os_buffered","numberOfSteps":1}""",
            """{"schemaVersion":1,"provenance":"live","numberOfSteps":1}""",
            """{"schemaVersion":1,"provenance":"os_buffered","numberOfSteps":-1}""",
            """{"schemaVersion":1,"provenance":"os_buffered","numberOfSteps":1,"distanceMeters":-0.1}""",
        )

        invalidPayloads.forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                mapSensorDataToStorage(listOf(sensorSample(timestamp, payload, SensorType.pedometer)))
            }
        }
    }

    @Test
    fun preservesValidatedMotionActivityPayload() {
        val timestamp = OffsetDateTime.parse("2026-07-12T03:10:17Z")
        val payload = """{"schemaVersion":1,"provenance":"os_buffered","stationary":false,"walking":true,"running":false,"automotive":false,"cycling":false,"unknown":false,"confidence":"high"}"""
        val sample = sensorSample(timestamp, payload, SensorType.motionActivity)

        val stored = mapSensorDataToStorage(listOf(sample))
            .getValue(SensorType.motionActivity)
            .single()
            .associate { it.col.name to it.value }

        assertEquals(payload, stored.getValue(PostgresEventColumns.RAW_SENSOR_PAYLOAD.name))
        assertEquals("motionActivity", stored.getValue(PostgresEventColumns.SENSOR_TYPE.name))
    }

    @Test
    fun preservesEverySensorTypeInMixedUploadBatch() {
        val timestamp = OffsetDateTime.parse("2026-07-12T03:10:17Z")
        val pedometer = sensorSample(
            timestamp,
            """{"schemaVersion":1,"provenance":"os_buffered","numberOfSteps":267}""",
            SensorType.pedometer,
        )
        val motionActivity = sensorSample(
            timestamp.plusSeconds(1),
            """{"schemaVersion":1,"provenance":"os_buffered","stationary":false,"walking":true,"running":false,"automotive":false,"cycling":false,"unknown":false,"confidence":"high"}""",
            SensorType.motionActivity,
        )

        val stored = mapSensorDataToStorage(listOf(pedometer, motionActivity))

        assertEquals(setOf(SensorType.pedometer, SensorType.motionActivity), stored.keys)
        assertEquals(1, stored.getValue(SensorType.pedometer).size)
        assertEquals(1, stored.getValue(SensorType.motionActivity).size)
    }

    @Test
    fun rejectsInvalidMotionActivityPayloads() {
        val timestamp = OffsetDateTime.parse("2026-07-12T03:10:17Z")
        val invalidPayloads = listOf(
            """{"schemaVersion":2,"provenance":"os_buffered","stationary":true,"walking":false,"running":false,"automotive":false,"cycling":false,"unknown":false,"confidence":"high"}""",
            """{"schemaVersion":1,"provenance":"live","stationary":true,"walking":false,"running":false,"automotive":false,"cycling":false,"unknown":false,"confidence":"high"}""",
            """{"schemaVersion":1,"provenance":"os_buffered","stationary":true,"walking":false,"running":false,"automotive":false,"cycling":false,"unknown":false,"confidence":"certain"}""",
            """{"schemaVersion":1,"provenance":"os_buffered","stationary":false,"walking":false,"running":false,"automotive":false,"cycling":false,"unknown":false,"confidence":"low"}""",
        )

        invalidPayloads.forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                mapSensorDataToStorage(listOf(sensorSample(timestamp, payload, SensorType.motionActivity)))
            }
        }
    }

    @Test
    fun rejectsMalformedCompactAccelerometerPayload() {
        val timestamp = OffsetDateTime.parse("2026-07-12T03:10:17Z")
        val payload = """{"schemaVersion":2,"encoding":"delta-zigzag-varint-zlib-base64","sampleCount":1,"provenance":"system_recorded","timeUnit":"nanoseconds","uncompressedByteCount":4,"channels":[{"name":"x","unit":"g","scale":0.000244140625},{"name":"y","unit":"g","scale":0.000244140625},{"name":"z","unit":"g","scale":0.000244140625}],"payload":"aW52YWxpZA=="}"""
        val sample = SensorDataSample(
            id = UUID.randomUUID(),
            dateRecorded = timestamp,
            duration = 0.02,
            data = payload,
            device = """{"model":"iPhone16,1","name":"U15","systemName":"iOS","systemVersion":"26.5"}""",
            timezone = "America/Chicago",
            sensor = SensorType.accelerometer,
            startDate = timestamp,
            endDate = timestamp.plusNanos(20_000_000),
        )

        assertThrows(IllegalArgumentException::class.java) {
            mapSensorDataToStorage(listOf(sample))
        }
    }

    @Test
    fun preservesScreenTimeMetadataInIosEventStorageRows() {
        val start = OffsetDateTime.parse("2026-06-24T01:00:00Z")
        val end = OffsetDateTime.parse("2026-06-24T02:00:00Z")
        val samples = ScreenTimeUsageUploadService.toSensorDataSamples(
            ScreenTimeUsageEnvelope(
                deviceId = "device-1",
                studyId = UUID.randomUUID().toString(),
                participantId = "participant-1",
                generatedAt = end,
                records = listOf(
                    ScreenTimeUsageRecord(
                        id = UUID.randomUUID(),
                        source = ScreenTimeCaptureSource.deviceActivityExport,
                        confidence = ScreenTimeConfidence.appleDeviceActivity,
                        rowKind = ScreenTimeUsageRowKind.application,
                        capturedAt = end,
                        observationStart = start,
                        observationEnd = end,
                        timezoneIdentifier = "UTC",
                        appName = "Instagram",
                        bundleIdentifier = "com.burbn.instagram",
                        categoryName = "Social",
                        durationSeconds = 120,
                        notificationCount = 3,
                        pickupCount = 2,
                        rawSourceLabel = "DeviceActivityData application",
                    )
                ),
            ),
            "device-1",
        )

        val rows = mapSensorDataToStorage(samples).getValue(SensorType.deviceUsage)
        val stored = rows.single().associate { it.col.name to it.value }

        assertEquals("Instagram", stored.getValue(PostgresEventColumns.APP_CATEGORY.name))
        assertEquals("com.burbn.instagram", stored.getValue(PostgresEventColumns.BUNDLE_IDENTIFIER.name))
        assertEquals("deviceActivityExport", stored.getValue(PostgresEventColumns.IOS_SCREEN_TIME_SOURCE.name))
        assertEquals("appleDeviceActivity", stored.getValue(PostgresEventColumns.IOS_SCREEN_TIME_CONFIDENCE.name))
        assertEquals("application", stored.getValue(PostgresEventColumns.IOS_SCREEN_TIME_ROW_KIND.name))
        assertEquals("Instagram", stored.getValue(PostgresEventColumns.IOS_SCREEN_TIME_APP_LABEL.name))
        assertEquals("com.burbn.instagram", stored.getValue(PostgresEventColumns.IOS_SCREEN_TIME_BUNDLE_ID.name))
        assertEquals("DeviceActivityData application", stored.getValue(PostgresEventColumns.IOS_SCREEN_TIME_RAW_SOURCE_LABEL.name))
        assertEquals(3, stored.getValue(PostgresEventColumns.IOS_SCREEN_TIME_NOTIFICATION_COUNT.name))
        assertEquals(2, stored.getValue(PostgresEventColumns.IOS_SCREEN_TIME_PICKUP_COUNT.name))
        assertTrue(stored.containsKey(PostgresEventColumns.IOS_SCREEN_TIME_WEB_DOMAIN.name))
    }

    private fun sensorSample(
        timestamp: OffsetDateTime,
        payload: String,
        sensor: SensorType,
        duration: Double = 0.0,
    ) = SensorDataSample(
        id = UUID.randomUUID(),
        dateRecorded = timestamp,
        duration = duration,
        data = payload,
        device = """{"model":"iPhone16,1","name":"U15","systemName":"iOS","systemVersion":"26.5"}""",
        timezone = "America/Chicago",
        sensor = sensor,
        startDate = timestamp,
        endDate = timestamp.plusNanos((duration * 1_000_000_000).toLong()),
    )
}
