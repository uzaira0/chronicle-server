package com.openlattice.chronicle.mapstores.stats

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSDataSources
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Owns access to the participant-stats cache.
 *
 * The durable guard prevents cached reads from bypassing RLS and avoids new
 * writes while deletion is active. If quarantine wins the race after a guard
 * check, the post-operation check evicts the value and PostgreSQL's deletion
 * enforcement safely discards the stale write-behind entry.
 */
public interface ParticipantStatsCache {
    public fun get(studyId: UUID, participantId: String): ParticipantStats?

    public fun merge(stats: ParticipantStats)

    public fun <T> quarantineParticipant(
        studyId: UUID,
        participantId: String,
        transaction: () -> T,
    ): T

    public fun <T> quarantineStudy(
        studyId: UUID,
        transaction: () -> T,
    ): T
}

// reason: quarantine commits are authoritative; broad boundary catches keep
// best-effort cache cleanup from changing or masking the transaction outcome
@Suppress("TooGenericExceptionCaught")
public open class HazelcastParticipantStatsCache internal constructor(
    private val participantStats: IMap<ParticipantKey, ParticipantStats>,
    private val deletionBlocked: (ParticipantKey) -> Boolean,
) : ParticipantStatsCache {

    public constructor(
        storageResolver: StorageResolver,
        hazelcast: HazelcastInstance,
    ) : this(
        HazelcastMap.PARTICIPANT_STATS.getMap(hazelcast),
        ParticipantStatsDeletionGuard(
            RLSDataSources.wrapWithSystemContext(storageResolver.getPlatformStorage()),
        )::isBlocked,
    )

    override fun get(studyId: UUID, participantId: String): ParticipantStats? {
        val key = ParticipantKey(studyId, participantId)
        if (deletionBlocked(key)) {
            participantStats.evict(key)
            return null
        }

        val cached = participantStats[key] ?: return null
        return if (deletionBlocked(key)) {
            participantStats.evict(key)
            null
        } else {
            cached
        }
    }

    override fun merge(stats: ParticipantStats) {
        val key = ParticipantKey(stats.studyId, stats.participantId)
        if (deletionBlocked(key)) {
            participantStats.evict(key)
            return
        }

        mergeAtomically(key, stats)
        if (deletionBlocked(key)) {
            participantStats.evict(key)
        }
    }

    /*
     * A compare-and-set loop keeps the merge atomic without shipping a newly
     * serialized entry-processor type through the cluster. Participant stats
     * are monotonic (date extrema plus set unions), so retrying after a
     * concurrent update cannot lose or resurrect data.
     */
    private fun mergeAtomically(key: ParticipantKey, stats: ParticipantStats) {
        while (true) {
            val current = participantStats[key]
            if (current == null) {
                if (participantStats.putIfAbsent(key, stats) == null) {
                    return
                }
            } else if (participantStats.replace(key, current, mergeParticipantStats(current, stats))) {
                return
            }
        }
    }

    override fun <T> quarantineParticipant(
        studyId: UUID,
        participantId: String,
        transaction: () -> T,
    ): T {
        var transactionFailure: Throwable? = null
        try {
            return transaction()
        } catch (failure: Throwable) {
            transactionFailure = failure
            throw failure
        } finally {
            evictBestEffort(
                ParticipantKey(studyId, participantId),
                transactionFailure,
                "participant quarantine",
            )
        }
    }

    override fun <T> quarantineStudy(
        studyId: UUID,
        transaction: () -> T,
    ): T {
        var transactionFailure: Throwable? = null
        try {
            return transaction()
        } catch (failure: Throwable) {
            transactionFailure = failure
            throw failure
        } finally {
            val cachedStudyKeys = try {
                participantStats.keys.filterTo(mutableSetOf()) { it.studyId == studyId }
            } catch (cacheFailure: Exception) {
                handleCacheCleanupFailure(
                    transactionFailure,
                    cacheFailure,
                    "enumerating participant-stats keys after study quarantine",
                )
                emptySet()
            }
            cachedStudyKeys.forEach { key ->
                evictBestEffort(key, transactionFailure, "study quarantine")
            }
        }
    }

    private fun evictBestEffort(
        key: ParticipantKey,
        transactionFailure: Throwable?,
        quarantineScope: String,
    ) {
        try {
            participantStats.evict(key)
        } catch (cacheFailure: Exception) {
            handleCacheCleanupFailure(
                transactionFailure,
                cacheFailure,
                "evicting participant stats after $quarantineScope",
            )
        }
    }

    private fun handleCacheCleanupFailure(
        transactionFailure: Throwable?,
        cacheFailure: Exception,
        operation: String,
    ) {
        if (transactionFailure != null) {
            if (transactionFailure !== cacheFailure) {
                transactionFailure.addSuppressed(cacheFailure)
            }
            return
        }

        logger.error(
            "Durable quarantine committed, but cache cleanup failed while {}.",
            operation,
            cacheFailure,
        )
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(HazelcastParticipantStatsCache::class.java)
    }
}

/**
 * Uses the durable visibility function from V50/V68 and the permanent-erasure
 * tombstone state from V68.
 *
 * PostgreSQL remains authoritative and independently enforces both boundaries.
 */
internal class ParticipantStatsDeletionGuard(
    private val dataSource: HikariDataSource,
) {
    fun isBlocked(key: ParticipantKey): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(IS_DELETION_BLOCKED_SQL).use { statement ->
                statement.setObject(1, key.studyId)
                statement.setString(2, key.participantId)
                statement.setObject(3, key.studyId)
                statement.setString(4, key.participantId)
                statement.setString(5, key.studyId.toString())
                statement.setString(6, key.participantId)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next()) { "Participant-stats deletion guard returned no result" }
                    resultSet.getBoolean(1)
                }
            }
        }

    private companion object {
        private const val IS_DELETION_BLOCKED_SQL = """
            SELECT
                NOT chronicle_participant_data_visible(?, ?)
                OR EXISTS (
                    SELECT 1
                    FROM data_deletion_operations operation
                    WHERE operation.study_id = ?
                      AND operation.mode IN ('WITHDRAW_AND_ERASE', 'STUDY_ERASURE')
                      AND operation.status = 'COMPLETED'
                      AND (
                          operation.mode = 'STUDY_ERASURE'
                          OR operation.participant_id = ?
                          OR operation.participant_block_token = md5(? || ':' || ?)
                      )
                )
        """
    }
}

/**
 * Keeps legacy direct orchestrator tests focused on SQL behavior. Production
 * construction requires the Hazelcast-backed implementation.
 */
internal object TransactionOnlyParticipantStatsCache : ParticipantStatsCache {
    override fun get(studyId: UUID, participantId: String): ParticipantStats? =
        error("Transaction-only participant-stats cache does not support reads")

    override fun merge(stats: ParticipantStats) {
        error("Transaction-only participant-stats cache does not support writes")
    }

    override fun <T> quarantineParticipant(
        studyId: UUID,
        participantId: String,
        transaction: () -> T,
    ): T = transaction()

    override fun <T> quarantineStudy(studyId: UUID, transaction: () -> T): T =
        transaction()
}
