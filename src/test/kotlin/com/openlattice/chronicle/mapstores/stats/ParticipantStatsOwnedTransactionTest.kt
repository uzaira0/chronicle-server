package com.openlattice.chronicle.mapstores.stats

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.jdbc.DataSourceManager
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.hazelcast.processors.storage.StudyStorageRead
import com.openlattice.chronicle.study.Study
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ParticipantStatsOwnedTransactionTest {
    @Test
    fun `cold stats read and merge use the owner and delayed stale store preserves merged dates`() {
        val postgres = ChronicleContractTestSchema.sharedPostgres
        val executor = Executors.newSingleThreadExecutor()
        try {
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl; username = postgres.username; password = postgres.password
            maximumPoolSize = 1; minimumIdle = 0; connectionTimeout = 250
        }).use { pool ->
            val manager = mock<DataSourceManager>()
            whenever(manager.getDataSource(any())).thenReturn(pool)
            whenever(manager.getFlavor(any())).thenReturn(PostgresFlavor.VANILLA)
            val storage = StorageResolver(manager, ChronicleStorageConfiguration(defaultEventStorage = "default"))
            val member = mock<HazelcastInstance>()
            val map = mock<IMap<ParticipantKey, ParticipantStats>>()
            whenever(member.getMap<ParticipantKey, ParticipantStats>("PARTICIPANT_STATS")).thenReturn(map)
            val studies = mock<IMap<UUID, Study>>()
            whenever(member.getMap<UUID, Study>("STUDIES")).thenReturn(studies)
            whenever(studies.executeOnKey(any(), any<StudyStorageRead>())).thenAnswer {
                executor.submit<String> { pool.connection.use { "default" } }.get(2, TimeUnit.SECONDS)
            }
            storage.setStudyStorage(member)
            val mapstore = ParticipantStatsMapstore(storage.getPlatformStorage())
            // A cold member-side load runs on a different thread and cannot inherit the owner's pin.
            whenever(map[any()]).thenAnswer { call ->
                executor.submit<ParticipantStats?> { mapstore.load(call.getArgument(0)) }.get(2, TimeUnit.SECONDS)
            }
            val cache = HazelcastParticipantStatsCache(storage, member)
            val study = UUID.randomUUID()
            val key = ParticipantKey(study, "single-slot-stats")
            val first = ParticipantStats(study, key.participantId, androidUniqueDates = setOf(LocalDate.parse("2026-09-01")))
            val next = first.copy(androidUniqueDates = setOf(LocalDate.parse("2026-09-02")))
            postgres.createConnection("").use { connection -> connection.createStatement().use {
                it.execute("INSERT INTO studies (study_id, title) VALUES ('$study', 'owned stats')")
            } }
            RLSRequestContext.withSystemContext {
                mapstore.store(key, first)
                val source = storage.getPlatformStorage()
                source.connection.use { owner ->
                    owner.autoCommit = false
                    PinnedPlatformConnection.pinning(source, owner) {
                        assertEquals("default", storage.resolveDataSourceName(study))
                        assertEquals(first.androidUniqueDates, cache.get(study, key.participantId)!!.androidUniqueDates)
                        cache.merge(next)
                        assertEquals(first.androidUniqueDates + next.androidUniqueDates,
                            cache.get(study, key.participantId)!!.androidUniqueDates)
                        assertEquals(1, pool.hikariPoolMXBean.activeConnections)
                    }
                    owner.commit()
                }
                mapstore.store(key, first)
                assertEquals(first.androidUniqueDates + next.androidUniqueDates, mapstore.load(key)!!.androidUniqueDates)
            }
            Mockito.verify(map, Mockito.never()).get(any())
            Mockito.verify(studies, Mockito.never()).executeOnKey(any(), any<StudyStorageRead>())
        }
        } finally { executor.shutdownNow() }
    }
}
