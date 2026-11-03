package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.services.delete.ServerSweepTestFixture
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.IOS_SCREEN_TIME_USAGE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UPLOADED_AT
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** What one upload stores in `ios_screen_time_usage`, against the migrated schema. */
class ScreenTimeUsageUploadServiceTest : ServerSweepTestFixture() {
    private val start = OffsetDateTime.parse("2026-06-24T01:00:00Z")
    private val end = start.plusHours(1)
    private val device = UUID.randomUUID()

    private fun app(name: String?, bundle: String? = null) = ScreenTimeUsageRecord(
        id = UUID.randomUUID(),
        rowKind = ScreenTimeUsageRowKind.application,
        capturedAt = end.plusMinutes(5),
        observationStart = start,
        observationEnd = end,
        timezoneIdentifier = "America/Santiago",
        appName = name,
        bundleIdentifier = bundle,
        categoryName = "Social",
        durationSeconds = 120,
    )

    private fun envelope(study: UUID, vararg records: ScreenTimeUsageRecord) = ScreenTimeUsageEnvelope(
        randomDeviceId = "device-1", studyId = study.toString(), participantId = PARTICIPANT, generatedAt = end,
        records = records.toList(),
    )

    private fun withStudy(test: (UUID, ScreenTimeUsageUploadService) -> Unit) {
        val study = UUID.randomUUID()
        seed(study, PARTICIPANT)
        pool("screen-time-upload").use { test(study, ScreenTimeUsageUploadService(resolver(it))) }
    }

    private fun storedApps(study: UUID): List<String?> = postgres.createConnection("").use { connection ->
        connection.prepareStatement(
            "SELECT coalesce(app_name, web_domain) FROM ios_screen_time_usage WHERE study_id = ?",
        ).use {
            it.setObject(1, study)
            it.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
        }
    }

    @Test fun everyRecordFieldIsStoredAsSent() = withStudy { study, service ->
        val record = app("Chronicle for Research").copy(notificationCount = 3, pickupCount = 4)
        assertEquals(1, service.upload(study, PARTICIPANT, device, envelope(study, record)))
        postgres.createConnection("").use { connection ->
            connection.prepareStatement("SELECT * FROM ios_screen_time_usage WHERE study_id = ?").use {
                it.setObject(1, study)
                it.executeQuery().use { row ->
                    row.next()
                    assertEquals(PARTICIPANT, row.getString("participant_id"))
                    assertEquals(device, row.getObject("random_device_id", UUID::class.java))
                    assertEquals(record.id, row.getObject("record_id", UUID::class.java))
                    assertEquals("application", row.getString("row_kind"))
                    assertEquals(record.capturedAt.toInstant(), row.getObject("captured_at", OffsetDateTime::class.java).toInstant())
                    assertEquals(start.toInstant(), row.getObject("observation_start", OffsetDateTime::class.java).toInstant())
                    assertEquals(end.toInstant(), row.getObject("observation_end", OffsetDateTime::class.java).toInstant())
                    assertEquals("America/Santiago", row.getString("timezone"))
                    assertEquals("Chronicle for Research", row.getString("app_name"))
                    assertEquals("a missing bundle id stays missing", null, row.getString("bundle_identifier"))
                    assertEquals(null, row.getString("web_domain"))
                    assertEquals("Social", row.getString("category_name"))
                    assertEquals(120, row.getInt("duration_seconds"))
                    assertEquals(3, row.getInt("notification_count"))
                    assertEquals(4, row.getInt("pickup_count"))
                    assertNotNull(row.getObject("uploaded_at"))
                }
            }
        }
    }

    /** An app row names an app and no domain; a web-domain row names a domain and no app. */
    @Test fun labelsContradictingTheRowKindRejectTheRow() = withStudy { study, service ->
        val web = app(null).copy(rowKind = ScreenTimeUsageRowKind.webDomain, webDomain = "example.com")
        listOf(
            app("Maps").copy(webDomain = "example.com"),
            app(null),
            app(" ", ""),
            web.copy(webDomain = null),
            web.copy(appName = "Safari"),
            web.copy(bundleIdentifier = "com.apple.mobilesafari"),
        ).forEach { invalid ->
            assertThrows(invalid.toString(), UploadRowRejectedException::class.java) {
                service.upload(study, PARTICIPANT, device, envelope(study, app("Instagram", "com.burbn.instagram"), invalid))
            }
        }
        assertEquals("a refused batch stores none of its rows", emptyList<String>(), storedApps(study))
        assertEquals(3, service.upload(study, PARTICIPANT, device,
            envelope(study, app("Instagram"), app(null, "com.burbn.instagram"), web)))
    }

    @Test fun appleLabelsWithMissingBundleIdsAndEmptyDomainsAreStored() = withStudy { study, service ->
        val application = app("Application")
        val web = app(null).copy(rowKind = ScreenTimeUsageRowKind.webDomain, webDomain = "")
        assertEquals(2, service.upload(study, PARTICIPANT, device, envelope(study, application, web)))
        assertEquals(setOf("Application", ""), storedApps(study).toSet())
    }

    @Test fun everyColumnButTheReceiptInstantIsBoundOnce() {
        val bound = ScreenTimeUsageUploadService.BINDINGS.map { it.first }
        assertEquals(bound.distinct(), bound)
        assertEquals((IOS_SCREEN_TIME_USAGE.columns - UPLOADED_AT).toSet(), bound.toSet())
    }

    @Test fun theTableHoldsOnlyWhatTheBareAppSends() {
        val columns = postgres.createConnection("").use { connection ->
            connection.prepareStatement(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'ios_screen_time_usage'",
            ).use { it.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toSet() } }
        }
        assertEquals(
            setOf(
                "study_id", "participant_id", "random_device_id", "record_id", "row_kind", "captured_at",
                "observation_start", "observation_end", "timezone", "app_name", "bundle_identifier", "web_domain",
                "category_name", "duration_seconds", "notification_count", "pickup_count", "uploaded_at",
                "capture_labels_hash",
            ),
            columns,
        )
    }

    @Test fun anUnknownTimezoneRejectsTheRowBeforeAnyWrite() = withStudy { study, service ->
        assertThrows(UploadRowRejectedException::class.java) {
            service.upload(study, PARTICIPANT, device,
                envelope(study, app("Maps"), app("Instagram").copy(timezoneIdentifier = "Invalid/Timezone")))
        }
        assertEquals(emptyList<String>(), storedApps(study))
    }

    @Test fun captureLabelHashesPreserveFieldBoundariesAndMissingValues() = withStudy { study, service ->
        val records = arrayOf(app("a", "bc"), app("ab", "c"), app("same"), app("same", ""))
        assertEquals(4, service.upload(study, PARTICIPANT, device, envelope(study, *records)))
        assertEquals(4, service.upload(study, PARTICIPANT, device,
            envelope(study, *records.map { it.copy(id = UUID.randomUUID()) }.toTypedArray())))
        assertEquals(4, storedApps(study).size)
    }

    private companion object {
        const val PARTICIPANT = "screen-time-service"
    }
}
