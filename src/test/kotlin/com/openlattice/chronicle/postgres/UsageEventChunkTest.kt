package com.openlattice.chronicle.postgres

import com.openlattice.chronicle.storage.ChroniclePostgresTables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.StringReader
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

class UsageEventChunkTest {
    @Test fun decodedEventsStayWithinTheChunkCeiling() {
        val rows = mock<ResultSet>()
        whenever(rows.getObject("study_id", UUID::class.java)).thenReturn(UUID.randomUUID())
        whenever(rows.getString("participant_id")).thenReturn("p1")
        whenever(rows.getObject("uploaded_at", OffsetDateTime::class.java)).thenReturn(OffsetDateTime.now())
        val count = ChroniclePostgresTables.ANDROID_DECODE_CHUNK_SIZE * 3 + 1
        whenever(rows.getCharacterStream("data")).thenReturn(StringReader(List(count) { "{}" }.joinToString(",", "[", "]")))
        var total = 0
        ResultSetAdapters.forEachUsageEventChunk(rows) {
            assertTrue(it.size in 1..ChroniclePostgresTables.ANDROID_DECODE_CHUNK_SIZE)
            total += it.size
        }
        assertEquals(count, total)
    }

    @Test fun oversizedHistoricalRowFailsBeforeLoadingAnyPayload() {
        val rows = mock<ResultSet>()
        whenever(rows.getLong(ChroniclePostgresTables.CLAIM_PAYLOAD_BYTES)).thenReturn(ChroniclePostgresTables.MAX_ANDROID_CLAIM_BYTES + 1)
        assertThrows(IllegalStateException::class.java) { ResultSetAdapters.forEachUsageEventChunk(rows) { fail("oversized row must not decode") } }
        verify(rows, never()).getCharacterStream(any<String>())
    }
}
