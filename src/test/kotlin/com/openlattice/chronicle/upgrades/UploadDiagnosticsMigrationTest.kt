package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.collection.AndroidUploadIssueCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class UploadDiagnosticsMigrationTest {
    private fun event(moduleFamily: String, issueCode: String) = AndroidUploadDiagnosticEvent(
        id = UUID.randomUUID().toString(),
        day = LocalDate.parse("2026-09-24"),
        moduleFamily = moduleFamily,
        issueCode = issueCode,
        count = 3,
        firstOccurredAt = OffsetDateTime.parse("2026-09-24T10:00:00Z"),
        lastOccurredAt = OffsetDateTime.parse("2026-09-24T10:00:00Z"),
    )

    @Test
    fun currentDiagnosticCodesAreKeptAndRawDetailsAreNeverStored() {
        for ((family, code) in listOf(
            "SENSOR" to "SAMPLE_QUARANTINED",
            "APP_RUNTIME" to "APP_CRASH",
            "APP_RUNTIME" to "APP_CRASH_NATIVE",
            "APP_RUNTIME" to "APP_ANR",
            "USAGE_LIFECYCLE" to "LOCAL_BUFFER_OVERFLOW",
            "INTERACTION" to "COLLECTION_ACCESS_MISSING",
        )) {
            assertEquals(code, event(family, code).recognizedIssueCode().name)
        }
        // Text that is not a closed code (an unknown code, or raw failure detail) is stored as the closed code only.
        for (raw in listOf("RAW_STACK_TRACE", "java.lang.IllegalStateException: participant p-17 at Upload.kt:12")) {
            assertEquals(AndroidUploadIssueCode.UNRECOGNIZED_ISSUE, event("SENSOR", raw).recognizedIssueCode())
        }
        assertThrows(IllegalArgumentException::class.java) { event("RAW_STACK_TRACE", "APP_CRASH") }
    }

    @Test
    fun diagnosticRowsKeepOnlyTheMinimizedFields() {
        val columns = migrationColumnNames("upload_diagnostics")
        assertTrue(columns.containsAll(setOf("study_id", "participant_id", "random_device_id", "event_id")))
        assertTrue("server_origin" !in columns)
        assertTrue("error_message" !in columns)
    }
}
