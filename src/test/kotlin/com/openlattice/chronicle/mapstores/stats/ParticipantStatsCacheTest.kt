package com.openlattice.chronicle.mapstores.stats

import com.hazelcast.map.IMap
import com.openlattice.chronicle.participants.ParticipantStats
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class ParticipantStatsCacheTest {
    private val participantStats = mock<IMap<ParticipantKey, ParticipantStats>>()

    @Test
    fun `participant quarantine evicts the cached value after the transaction`() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-a"
        val key = ParticipantKey(studyId, participantId)
        val events = mutableListOf<String>()
        val cache = cache(deletionBlocked = false)

        doAnswer {
            events += "evict"
            true
        }.whenever(participantStats).evict(key)

        val result = cache.quarantineParticipant(studyId, participantId) {
            events += "transaction"
            "operation-id"
        }

        assertEquals("operation-id", result)
        assertEquals(listOf("transaction", "evict"), events)
    }

    @Test
    fun `participant eviction failure cannot turn durable quarantine into a failed response`() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-a"
        val key = ParticipantKey(studyId, participantId)
        val cache = cache(deletionBlocked = false)
        doAnswer { throw IllegalStateException("injected cache failure") }
            .whenever(participantStats)
            .evict(key)

        val result = cache.quarantineParticipant(studyId, participantId) { "operation-id" }

        assertEquals("operation-id", result)
    }

    @Test
    fun `participant eviction failure is suppressed on the primary transaction failure`() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-a"
        val key = ParticipantKey(studyId, participantId)
        val transactionFailure = IllegalStateException("injected transaction failure")
        val cacheFailure = IllegalArgumentException("injected cache failure")
        val cache = cache(deletionBlocked = false)
        doAnswer { throw cacheFailure }.whenever(participantStats).evict(key)

        val actual = assertThrows(IllegalStateException::class.java) {
            cache.quarantineParticipant(studyId, participantId) {
                throw transactionFailure
            }
        }

        assertSame(transactionFailure, actual)
        assertEquals(1, actual.suppressed.size)
        assertSame(cacheFailure, actual.suppressed.single())
    }

    @Test
    fun `study quarantine evicts only cached keys in the target study`() {
        val studyId = UUID.randomUUID()
        val targetKeys = setOf(
            ParticipantKey(studyId, "participant-a"),
            ParticipantKey(studyId, "participant-b"),
        )
        val otherKey = ParticipantKey(UUID.randomUUID(), "participant-c")
        whenever(participantStats.keys).thenReturn((targetKeys + otherKey).toMutableSet())
        val cache = cache(deletionBlocked = false)

        cache.quarantineStudy(studyId) { "operation-id" }

        targetKeys.forEach { verify(participantStats).evict(it) }
        verify(participantStats, never()).evict(otherKey)
    }

    @Test
    fun `study quarantine continues eviction after one cache failure`() {
        val studyId = UUID.randomUUID()
        val firstKey = ParticipantKey(studyId, "participant-a")
        val secondKey = ParticipantKey(studyId, "participant-b")
        val otherKey = ParticipantKey(UUID.randomUUID(), "participant-c")
        whenever(participantStats.keys).thenReturn(linkedSetOf(firstKey, secondKey, otherKey))
        doAnswer { throw IllegalStateException("injected cache failure") }
            .whenever(participantStats)
            .evict(firstKey)
        val cache = cache(deletionBlocked = false)

        val result = cache.quarantineStudy(studyId) { "operation-id" }

        assertEquals("operation-id", result)
        verify(participantStats).evict(firstKey)
        verify(participantStats).evict(secondKey)
        verify(participantStats, never()).evict(otherKey)
    }

    @Test
    fun `study eviction failure preserves the transaction failure and continues cleanup`() {
        val studyId = UUID.randomUUID()
        val firstKey = ParticipantKey(studyId, "participant-a")
        val secondKey = ParticipantKey(studyId, "participant-b")
        val transactionFailure = IllegalStateException("injected transaction failure")
        val cacheFailure = IllegalArgumentException("injected cache failure")
        whenever(participantStats.keys).thenReturn(linkedSetOf(firstKey, secondKey))
        doAnswer { throw cacheFailure }.whenever(participantStats).evict(firstKey)
        val cache = cache(deletionBlocked = false)

        val actual = assertThrows(IllegalStateException::class.java) {
            cache.quarantineStudy(studyId) {
                throw transactionFailure
            }
        }

        assertSame(transactionFailure, actual)
        assertEquals(1, actual.suppressed.size)
        assertSame(cacheFailure, actual.suppressed.single())
        verify(participantStats).evict(firstKey)
        verify(participantStats).evict(secondKey)
    }

    @Test
    fun `study key enumeration failure cannot turn durable quarantine into a failed response`() {
        val studyId = UUID.randomUUID()
        whenever(participantStats.keys).thenThrow(IllegalStateException("injected cache failure"))
        val cache = cache(deletionBlocked = false)

        val result = cache.quarantineStudy(studyId) { "operation-id" }

        assertEquals("operation-id", result)
    }

    @Test
    fun `blocked merge is evicted instead of reaching the map store`() {
        val stats = ParticipantStats(UUID.randomUUID(), "withdrawn-participant")
        val key = ParticipantKey(stats.studyId, stats.participantId)
        val cache = cache(deletionBlocked = true)

        cache.merge(stats)

        verify(participantStats).evict(key)
        verify(participantStats, never()).get(key)
        verify(participantStats, never()).putIfAbsent(eq(key), any())
        verify(participantStats, never()).replace(eq(key), any(), any())
    }

    @Test
    fun `allowed merge inserts a missing participant stats value atomically`() {
        val stats = ParticipantStats(UUID.randomUUID(), "active-participant")
        val key = ParticipantKey(stats.studyId, stats.participantId)
        val cache = cache(deletionBlocked = false)

        cache.merge(stats)

        verify(participantStats).get(key)
        verify(participantStats).putIfAbsent(key, stats)
    }

    @Test
    fun `allowed merge retries a concurrent compare-and-set loss without losing either update`() {
        val incomingDate = LocalDate.parse("2026-07-29")
        val incoming = ParticipantStats(
            UUID.randomUUID(),
            "active-participant",
            androidUniqueDates = setOf(incomingDate),
        )
        val key = ParticipantKey(incoming.studyId, incoming.participantId)
        val firstDate = LocalDate.parse("2026-07-27")
        val concurrentDate = LocalDate.parse("2026-07-28")
        val first = incoming.copy(androidUniqueDates = setOf(firstDate))
        val concurrent = incoming.copy(androidUniqueDates = setOf(concurrentDate))
        val firstAttempt = mergeParticipantStats(first, incoming)
        val secondAttempt = mergeParticipantStats(concurrent, incoming)
        assertEquals(setOf(firstDate, incomingDate), firstAttempt.androidUniqueDates)
        assertEquals(setOf(concurrentDate, incomingDate), secondAttempt.androidUniqueDates)
        whenever(participantStats[key]).thenReturn(first, concurrent)
        whenever(
            participantStats.replace(
                key,
                first,
                firstAttempt,
            ),
        ).thenReturn(false)
        whenever(
            participantStats.replace(
                key,
                concurrent,
                secondAttempt,
            ),
        ).thenReturn(true)
        val cache = cache(deletionBlocked = false)

        cache.merge(incoming)

        verify(participantStats, times(2)).get(key)
        verify(participantStats).replace(key, first, firstAttempt)
        verify(participantStats).replace(key, concurrent, secondAttempt)
    }

    @Test
    fun `pure merge preserves extrema and unions every platform date set`() {
        val studyId = UUID.randomUUID()
        val early = OffsetDateTime.parse("2026-07-27T12:00:00Z")
        val middle = OffsetDateTime.parse("2026-07-28T12:00:00Z")
        val late = OffsetDateTime.parse("2026-07-29T12:00:00Z")
        val firstDate = early.toLocalDate()
        val secondDate = late.toLocalDate()
        val current = ParticipantStats(
            studyId = studyId,
            participantId = "participant-extrema",
            androidLastPing = middle,
            androidFirstDate = middle,
            androidLastDate = middle,
            androidUniqueDates = setOf(firstDate),
            iosLastPing = middle,
            iosFirstDate = middle,
            iosLastDate = middle,
            iosUniqueDates = setOf(firstDate),
            tudFirstDate = middle,
            tudLastDate = middle,
            tudUniqueDates = setOf(firstDate),
        )
        val incoming = current.copy(
            androidLastPing = late,
            androidFirstDate = early,
            androidLastDate = late,
            androidUniqueDates = setOf(secondDate),
            iosLastPing = late,
            iosFirstDate = early,
            iosLastDate = late,
            iosUniqueDates = setOf(secondDate),
            tudFirstDate = early,
            tudLastDate = late,
            tudUniqueDates = setOf(secondDate),
        )

        val merged = mergeParticipantStats(current, incoming)

        assertEquals(late, merged.androidLastPing)
        assertEquals(early, merged.androidFirstDate)
        assertEquals(late, merged.androidLastDate)
        assertEquals(setOf(firstDate, secondDate), merged.androidUniqueDates)
        assertEquals(late, merged.iosLastPing)
        assertEquals(early, merged.iosFirstDate)
        assertEquals(late, merged.iosLastDate)
        assertEquals(setOf(firstDate, secondDate), merged.iosUniqueDates)
        assertEquals(early, merged.tudFirstDate)
        assertEquals(late, merged.tudLastDate)
        assertEquals(setOf(firstDate, secondDate), merged.tudUniqueDates)
    }

    @Test
    fun `merge evicts a value when quarantine activates after the first guard check`() {
        val stats = ParticipantStats(UUID.randomUUID(), "racing-participant")
        val key = ParticipantKey(stats.studyId, stats.participantId)
        var guardChecks = 0
        val cache = HazelcastParticipantStatsCache(participantStats) {
            guardChecks++ > 0
        }

        cache.merge(stats)

        verify(participantStats).get(key)
        verify(participantStats).putIfAbsent(key, stats)
        verify(participantStats).evict(key)
        assertEquals(2, guardChecks)
    }

    @Test
    fun `allowed read returns the cached value`() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-a"
        val expected = ParticipantStats(studyId, participantId)
        whenever(participantStats[ParticipantKey(studyId, participantId)]).thenReturn(expected)
        val cache = cache(deletionBlocked = false)

        val actual = cache.get(studyId, participantId)

        assertSame(expected, actual)
    }

    @Test
    fun `blocked read evicts stale data instead of bypassing RLS`() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-a"
        val key = ParticipantKey(studyId, participantId)
        val cache = cache(deletionBlocked = true)

        val actual = cache.get(studyId, participantId)

        assertEquals(null, actual)
        verify(participantStats).evict(key)
        verify(participantStats, never()).get(key)
    }

    @Test
    fun `read does not return cached data when quarantine activates after lookup`() {
        val studyId = UUID.randomUUID()
        val participantId = "racing-participant"
        val key = ParticipantKey(studyId, participantId)
        val cached = ParticipantStats(studyId, participantId)
        var guardChecks = 0
        whenever(participantStats[key]).thenReturn(cached)
        val cache = HazelcastParticipantStatsCache(participantStats) {
            guardChecks++ > 0
        }

        val actual = cache.get(studyId, participantId)

        assertEquals(null, actual)
        verify(participantStats).get(key)
        verify(participantStats).evict(key)
        assertEquals(2, guardChecks)
    }

    @Test
    fun `failed quarantine still evicts stale cache data`() {
        val studyId = UUID.randomUUID()
        val participantId = "participant-a"
        val key = ParticipantKey(studyId, participantId)
        val cache = cache(deletionBlocked = false)

        assertThrows(IllegalStateException::class.java) {
            cache.quarantineParticipant(studyId, participantId) {
                throw IllegalStateException("injected transaction failure")
            }
        }

        verify(participantStats).evict(key)
    }

    @Test
    fun `durable mutation guard binds both participant identity forms`() {
        val dataSource = mock<HikariDataSource>()
        val connection = mock<Connection>()
        val statement = mock<PreparedStatement>()
        val resultSet = mock<ResultSet>()
        val key = ParticipantKey(UUID.randomUUID(), "participant-a")
        whenever(dataSource.connection).thenReturn(connection)
        whenever(connection.prepareStatement(any())).thenReturn(statement)
        whenever(statement.executeQuery()).thenReturn(resultSet)
        whenever(resultSet.next()).thenReturn(true)
        whenever(resultSet.getBoolean(1)).thenReturn(true)

        val blocked = ParticipantStatsDeletionGuard(dataSource).isBlocked(key)

        assertEquals(true, blocked)
        verify(statement).setObject(1, key.studyId)
        verify(statement).setString(2, key.participantId)
        verify(statement).setObject(3, key.studyId)
        verify(statement).setString(4, key.participantId)
        verify(statement).setString(5, key.studyId.toString())
        verify(statement).setString(6, key.participantId)
    }

    private fun cache(deletionBlocked: Boolean): HazelcastParticipantStatsCache =
        HazelcastParticipantStatsCache(
            participantStats,
        ) { deletionBlocked }
}
