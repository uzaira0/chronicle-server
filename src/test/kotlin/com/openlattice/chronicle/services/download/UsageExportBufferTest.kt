package com.openlattice.chronicle.services.download

import com.openlattice.chronicle.storage.ResolvedStorage

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.services.delete.ServerSweepTestFixture
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.util.tests.TestDataFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime
import java.util.UUID

class UsageExportBufferTest : ServerSweepTestFixture() {
    @Test fun storedUsageExportsItsClientEventIdentity() {
        val study = UUID.randomUUID()
        val participant = "buffered-export"
        seed(study, participant)
        pool("buffered-export").use { pool ->
            val storage = mock<StorageResolver>()
            whenever(storage.getPlatformStorage()).thenReturn(pool)
            whenever(storage.getPlatformReadStorage()).thenReturn(pool)
            whenever(storage.resolveAndGetFlavor(study)).thenReturn(ResolvedStorage(PostgresFlavor.VANILLA, pool))
            whenever(storage.getEventStorageWithFlavor(PostgresFlavor.VANILLA)).thenReturn(pool)
            val upload = AppDataUploadService(storage, mock())
            val now = OffsetDateTime.now().withNano(0)
            val event = (TestDataFactory.chronicleUsageEvents(study, participant, 1).single() as ChronicleUsageEvent).copy(
                timestamp = now, timezone = "America/Santiago", clientEventId = UUID.randomUUID(),
            )
            assertEquals(setOf(event.clientEventId), upload.uploadAndroidUsageEvents(study, participant, UUID.randomUUID(), listOf(event)))
            val download = DataDownloadService(storage)
            upload.drainEventStorage()
            val rows = download.getParticipantsUsageEventsData(study, emptySet(), now.minusDays(1), now.plusDays(1)).toList()
            assertEquals(1, rows.size)
            assertEquals(event.clientEventId.toString(), rows.single()["client_event_id"])
        }
    }
}
