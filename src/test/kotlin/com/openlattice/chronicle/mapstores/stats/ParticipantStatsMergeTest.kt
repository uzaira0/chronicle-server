package com.openlattice.chronicle.mapstores.stats

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.participants.ParticipantStats
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.mockito.kotlin.mock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A delayed cache write never moves a first date later, a last date earlier, or drops a collection day. */
class ParticipantStatsMergeTest {
    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("participant_stats_merge")

        @BeforeClass
        @JvmStatic
        fun migrate() = ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)

        @AfterClass
        @JvmStatic
        fun clear() = ChronicleContractTestSchema.clearSchema(postgres)
    }

    @Test
    fun aStaleWriteMergesIntoTheStoredRow() {
        val early = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val late = early.plusDays(9)
        val stored = ParticipantStats(
            studyId = UUID.randomUUID(),
            participantId = "participant",
            androidLastPing = late,
            androidFirstDate = early,
            androidLastDate = late,
            androidUniqueDates = setOf(LocalDate.of(2026, 1, 1)),
            iosLastPing = late,
            iosFirstDate = early,
            iosLastDate = late,
            iosUniqueDates = setOf(LocalDate.of(2026, 1, 2)),
            tudFirstDate = early,
            tudLastDate = late,
            tudUniqueDates = setOf(LocalDate.of(2026, 1, 3)),
        )
        val stale = stored.copy(
            androidLastPing = early,
            androidFirstDate = late,
            androidLastDate = early,
            androidUniqueDates = setOf(LocalDate.of(2026, 1, 4)),
            iosLastPing = early,
            iosFirstDate = late,
            iosLastDate = early,
            iosUniqueDates = setOf(LocalDate.of(2026, 1, 5)),
            tudFirstDate = late,
            tudLastDate = early,
            tudUniqueDates = setOf(LocalDate.of(2026, 1, 6)),
        )

        postgres.createConnection("").use { connection ->
            val mapstore = ParticipantStatsMapstore(mock())
            mapstore.mergeUsing(connection, stored)
            mapstore.mergeUsing(connection, stale)

            val merged = mapstore.readUsing(connection, ParticipantKey(stored.studyId, stored.participantId))

            assertEquals(
                stored.copy(
                    androidUniqueDates = stored.androidUniqueDates + stale.androidUniqueDates,
                    iosUniqueDates = stored.iosUniqueDates + stale.iosUniqueDates,
                    tudUniqueDates = stored.tudUniqueDates + stale.tudUniqueDates,
                ),
                merged?.let(::utc),
            )
        }
    }

    private fun utc(stats: ParticipantStats) = stats.copy(
        androidLastPing = stats.androidLastPing?.withOffsetSameInstant(ZoneOffset.UTC),
        androidFirstDate = stats.androidFirstDate?.withOffsetSameInstant(ZoneOffset.UTC),
        androidLastDate = stats.androidLastDate?.withOffsetSameInstant(ZoneOffset.UTC),
        iosLastPing = stats.iosLastPing?.withOffsetSameInstant(ZoneOffset.UTC),
        iosFirstDate = stats.iosFirstDate?.withOffsetSameInstant(ZoneOffset.UTC),
        iosLastDate = stats.iosLastDate?.withOffsetSameInstant(ZoneOffset.UTC),
        tudFirstDate = stats.tudFirstDate?.withOffsetSameInstant(ZoneOffset.UTC),
        tudLastDate = stats.tudLastDate?.withOffsetSameInstant(ZoneOffset.UTC),
    )
}
