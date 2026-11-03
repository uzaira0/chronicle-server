package com.openlattice.chronicle.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.services.upload.UploadType

/** Every combination of purge cutoff and collection time, with the admission each must get. */
class ParticipantPurgeCutoffTest {
    private val cutoff = OffsetDateTime.of(2026, 3, 1, 12, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun admissionFollowsTheCutoffForEveryCase() {
        val cases = listOf(
            Triple(null, null, true),
            Triple(null, cutoff.minusDays(1), true),
            Triple(cutoff, null, false),
            Triple(cutoff, cutoff.minusNanos(1), false),
            Triple(cutoff, cutoff, true),
            Triple(cutoff, cutoff.withOffsetSameInstant(ZoneOffset.ofHours(-4)), true),
            Triple(cutoff, cutoff.plusNanos(1), true),
        )

        cases.forEach { (purgeCutoff, collectedAt, admitted) ->
            assertEquals("cutoff=$purgeCutoff collectedAt=$collectedAt", admitted,
                ParticipantPurgeCutoff.permits(purgeCutoff, collectedAt))
        }
    }

    @Test
    fun quarantinedSensorPayloadsKeepTheirCollectionTimeForPurge() {
        val payload = ObjectMappers.newJsonMapper().readTree("""{"timestamp":"$cutoff"}""")
        assertEquals(cutoff, ParticipantPurgeCutoff.bufferedCollectionTime(payload, UploadType.AndroidSensorRejected))
    }
}
