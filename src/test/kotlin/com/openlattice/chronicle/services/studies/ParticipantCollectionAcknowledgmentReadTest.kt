package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.Study
import com.zaxxer.hikari.HikariDataSource
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ParticipantCollectionAcknowledgmentReadTest : ChronicleServerTests() {
    @Test fun researcherHistoryUsesOneReadSnapshotAcrossDevices() {
        val studyId = clientUser1.studyApi.createStudy(Study(title = "Consent history", contact = "test@openlattice.com"))
        hds.connection.use { connection ->
            connection.prepareStatement("""
                INSERT INTO participant_collection_acknowledgment
                    (id, study_id, participant_id, source_random_device_id, acknowledged_modules, acknowledged_at, decision_seq)
                VALUES (?, ?, 'history-only', ?, '[]', now(), 1)
            """.trimIndent()).use { statement ->
                for (device in listOf("first-device", "second-device")) {
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, studyId)
                    statement.setString(3, device)
                    statement.executeUpdate()
                }
            }
        }
        val borrows = AtomicInteger()
        val reads = mock<HikariDataSource>()
        whenever(reads.connection).thenAnswer { borrows.incrementAndGet(); hds.connection }
        val storage = mock<StorageResolver>()
        whenever(storage.getPlatformStorage()).thenReturn(reads)
        val entries = ParticipantCollectionAcknowledgmentService(storage).getAcknowledgments(studyId, 10, 0)
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.currentConsentedModules.isEmpty() })
        assertEquals("A history page must not open a transaction and device lock for every row", 1, borrows.get())
    }
}
