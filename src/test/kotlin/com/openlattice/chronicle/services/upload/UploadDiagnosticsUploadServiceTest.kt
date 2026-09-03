package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class UploadDiagnosticsUploadServiceTest {
    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private val service = UploadDiagnosticsUploadService(storageResolver)
    private val studyId = UUID.randomUUID()
    private val deviceId = UUID.randomUUID()

    @Test
    fun `empty upload is a storage-free no-op`() {
        assertEquals(emptyList<String>(), service.upload(studyId, "participant", deviceId, emptyList()))
        Mockito.verifyNoInteractions(storageResolver)
    }

    @Test
    fun `rejects oversized and duplicate batches before opening storage`() {
        val event = fixture()

        assertThrows(IllegalArgumentException::class.java) {
            service.upload(studyId, "participant", deviceId, List(501) { fixture() })
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.upload(studyId, "participant", deviceId, listOf(event, event))
        }
        Mockito.verifyNoInteractions(storageResolver)
    }

    private fun fixture(
        id: String = UUID.randomUUID().toString(),
    ): AndroidUploadDiagnosticEvent = AndroidUploadDiagnosticEvent(
        id = id,
        day = LocalDate.parse("2026-08-26"),
        moduleFamily = "USAGE_LIFECYCLE",
        issueCode = "CONNECTION_FAILURE",
        count = 1,
        firstOccurredAt = OffsetDateTime.parse("2026-08-26T12:00:00Z"),
        lastOccurredAt = OffsetDateTime.parse("2026-08-26T12:00:01Z"),
        errorType = "ConnectException",
    )
}
