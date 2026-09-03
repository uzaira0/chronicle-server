package com.openlattice.chronicle.mapstores.stats

import com.codahale.metrics.annotation.Timed
import com.geekbeast.postgres.PostgresArrays
import com.geekbeast.postgres.mapstores.AbstractBasePostgresMapstore
import com.hazelcast.config.EvictionConfig
import com.hazelcast.config.EvictionPolicy
import com.hazelcast.config.MapConfig
import com.hazelcast.config.MapStoreConfig
import com.hazelcast.config.MaxSizePolicy
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.postgres.ResultSetAdapters
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.PARTICIPANT_STATS
import com.openlattice.chronicle.util.tests.TestDataFactory
import com.zaxxer.hikari.HikariDataSource
import org.apache.commons.lang3.RandomStringUtils
import org.springframework.stereotype.Service
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@getmethodic.com&gt;
 */
@Service
public open class ParticipantStatsMapstore(hds: HikariDataSource) : AbstractBasePostgresMapstore<ParticipantKey, ParticipantStats>(
    HazelcastMap.PARTICIPANT_STATS,
    PARTICIPANT_STATS,
    hds
) {
    override fun getMapStoreConfig(): MapStoreConfig {
        return super.getMapStoreConfig()
            .setInitialLoadMode(MapStoreConfig.InitialLoadMode.LAZY)
            // Keep this stable across rolling upgrades. Hazelcast cannot safely
            // migrate a map between write-behind and write-through members.
            .setWriteDelaySeconds(5)
    }

    /**
     * A write queued before deletion quarantine can reach PostgreSQL after the
     * matching RLS policy or mutation trigger has become active. Hazelcast
     * retries every MapStore exception indefinitely, but this particular write
     * is permanently obsolete and must be acknowledged as discarded.
     *
     * Suppression is intentionally two-factor: the SQL failure must come from
     * Chronicle's exact deletion enforcement and the durable ledger must still
     * block this participant. Every other failure propagates for normal retry.
     */
    @Timed
    override fun store(key: ParticipantKey, value: ParticipantStats) {
        try {
            hds.connection.use { connection ->
                prepareInsert(connection).use { insertRow ->
                    bind(insertRow, key, value)
                    logger.debug("Insert query: {}", insertRow)
                    insertRow.execute()
                    handleStoreSucceeded(key, value)
                }
            }
        } catch (failure: SQLException) {
            if (isDeletionEnforcementFailure(failure) && deletionGuardConfirmsBlocked(key, failure)) {
                logger.debug(
                    "Discarded stale participant-stats write rejected by the durable deletion boundary for study {}",
                    key.studyId,
                )
                return
            }

            val message = "Error executing SQL during store for key $key in map $mapName."
            logger.error(message, failure)
            handleStoreFailed(key, value)
            throw IllegalStateException(message, failure)
        }
    }

    @Timed
    override fun storeAll(map: Map<ParticipantKey, ParticipantStats>) {
        var lastKey: ParticipantKey? = null
        try {
            hds.connection.use { connection ->
                prepareInsert(connection).use { insertRow ->
                    map.forEach { (key, value) ->
                        lastKey = key
                        bind(insertRow, key, value)
                        insertRow.addBatch()
                    }
                    insertRow.executeBatch()
                    handleStoreAllSucceeded(map)
                }
            }
        } catch (failure: SQLException) {
            if (isDeletionEnforcementFailure(failure)) {
                // Retry individually so only entries that independently fail
                // Chronicle's deletion boundary are discarded. Valid entries
                // retain normal persistence and unrelated failures propagate.
                map.forEach { (key, value) -> store(key, value) }
                return
            }

            val message = "Error executing SQL during store all for key $lastKey in map $mapName."
            logger.error(message, failure)
            throw IllegalStateException(message, failure)
        }
    }

    override fun getMapConfig(): MapConfig {
        return super.getMapConfig()
            .setEvictionConfig(
                EvictionConfig()
                    .setEvictionPolicy(EvictionPolicy.LRU)
                    .setMaxSizePolicy(MaxSizePolicy.PER_NODE)
                    .setSize(10_000)
            )
    }

    override fun bind(ps: PreparedStatement, key: ParticipantKey, value: ParticipantStats) {
        var offset = bind(ps, key)
//        PostgresColumns.STUDY_ID,
//        PostgresColumns.PARTICIPANT_ID,
//        PostgresColumns.ANDROID_LAST_PING,
//        PostgresColumns.ANDROID_FIRST_DATE,
//        PostgresColumns.ANDROID_LAST_DATE,
//        PostgresColumns.ANDROID_UNIQUE_DATES,
//        PostgresColumns.IOS_LAST_PING,
//        PostgresColumns.IOS_FIRST_DATE,
//        PostgresColumns.IOS_LAST_DATE,
//        PostgresColumns.IOS_UNIQUE_DATES,
//        PostgresColumns.TUD_FIRST_DATE,
//        PostgresColumns.TUD_LAST_DATE,
//        PostgresColumns.TUD_UNIQUE_DATES
        ps.setObject(offset++, value.androidLastPing)
        ps.setObject(offset++, value.androidFirstDate)
        ps.setObject(offset++, value.androidLastDate)
        ps.setArray(offset++, PostgresArrays.createDateArray(ps.connection, value.androidUniqueDates))
        ps.setObject(offset++, value.iosLastPing)
        ps.setObject(offset++, value.iosFirstDate)
        ps.setObject(offset++, value.iosLastDate)
        ps.setArray(offset++, PostgresArrays.createDateArray(ps.connection, value.iosUniqueDates))
        ps.setObject(offset++, value.tudFirstDate)
        ps.setObject(offset++, value.tudLastDate)
        ps.setArray(offset++, PostgresArrays.createDateArray(ps.connection, value.tudUniqueDates))

        //For update query
        ps.setObject(offset++, value.androidLastPing)
        ps.setObject(offset++, value.androidFirstDate)
        ps.setObject(offset++, value.androidLastDate)
        ps.setArray(offset++, PostgresArrays.createDateArray(ps.connection, value.androidUniqueDates))
        ps.setObject(offset++, value.iosLastPing)
        ps.setObject(offset++, value.iosFirstDate)
        ps.setObject(offset++, value.iosLastDate)
        ps.setArray(offset++, PostgresArrays.createDateArray(ps.connection, value.iosUniqueDates))
        ps.setObject(offset++, value.tudFirstDate)
        ps.setObject(offset++, value.tudLastDate)
        ps.setArray(offset++, PostgresArrays.createDateArray(ps.connection, value.tudUniqueDates))

    }

    override fun bind(ps: PreparedStatement, key: ParticipantKey, offset: Int): Int {
        ps.setObject(offset, key.studyId)
        ps.setString(offset + 1, key.participantId)
        return offset + 2
    }

    @Suppress("DEPRECATION")
    override fun generateTestKey(): ParticipantKey =
        ParticipantKey(
            UUID.randomUUID(),
            RandomStringUtils.randomAlphanumeric(8)
        )

    override fun generateTestValue(): ParticipantStats = TestDataFactory.participantStats()

    override fun mapToKey(rs: ResultSet): ParticipantKey {
        return ResultSetAdapters.participantKey(rs)
    }

    override fun mapToValue(rs: ResultSet): ParticipantStats {
        return ResultSetAdapters.participantStats(rs)
    }

    private fun deletionGuardConfirmsBlocked(
        key: ParticipantKey,
        originalFailure: SQLException,
    ): Boolean =
        try {
            ParticipantStatsDeletionGuard(hds).isBlocked(key)
        } catch (guardFailure: Exception) {
            originalFailure.addSuppressed(guardFailure)
            false
        }

    private fun isDeletionEnforcementFailure(failure: SQLException): Boolean {
        val pending = ArrayDeque<SQLException>()
        val seen = Collections.newSetFromMap(IdentityHashMap<SQLException, Boolean>())
        pending.add(failure)

        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!seen.add(current)) {
                continue
            }

            val message = current.message.orEmpty()
            if (
                current.sqlState == RLS_REJECTION_SQL_STATE &&
                message.contains(PARTICIPANT_STATS_QUARANTINE_POLICY)
            ) {
                return true
            }
            if (
                current.sqlState == MUTATION_REJECTION_SQL_STATE &&
                message.contains(DELETION_MUTATION_REJECTION)
            ) {
                return true
            }

            current.nextException?.let(pending::addLast)
            (current.cause as? SQLException)?.let(pending::addLast)
        }

        return false
    }

    private companion object {
        private const val RLS_REJECTION_SQL_STATE = "42501"
        private const val MUTATION_REJECTION_SQL_STATE = "55000"
        private const val PARTICIPANT_STATS_QUARANTINE_POLICY =
            "deletion_quarantine_participant_stats"
        private const val DELETION_MUTATION_REJECTION =
            "Participant data mutation is blocked by an erasure operation"
    }
}
