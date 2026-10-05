package com.openlattice.chronicle.hazelcast.serializers

import com.hazelcast.internal.serialization.impl.DefaultSerializationServiceBuilder
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.serializers.ParticipantStatsStreamSerializer
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/** IMap.replace(key, old, new) compares bytes; a value read back must re-serialize identically. */
class ParticipantStatsCanonicalBytesTest {
    @Test
    fun readBackStatsSerializeToTheStoredBytes() {
        val serializer = ParticipantStatsStreamSerializer()
        val ss = DefaultSerializationServiceBuilder().build()
        fun bytes(stats: ParticipantStats) = ss.createObjectDataOutput(1).also { serializer.write(it, stats) }.toByteArray()
        val dates = (0L until 30L).map { LocalDate.of(2026, 10, 2).minusDays(it) }.toCollection(LinkedHashSet())
        val stored = bytes(ParticipantStats(UUID.randomUUID(), "p", androidUniqueDates = dates, tudUniqueDates = dates))

        val readBack = serializer.read(ss.createObjectDataInput(stored))

        assertArrayEquals(stored, bytes(readBack))
    }
}
