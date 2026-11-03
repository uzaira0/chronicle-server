package com.openlattice.chronicle.services.studies

import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.postgres.PostgresArrays
import com.geekbeast.postgres.streams.BasePostgresIterable
import com.geekbeast.postgres.streams.PreparedStatementHolderSupplier
import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.postgres.ResultSetAdapters
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.STUDIES
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.STUDY_LIMITS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DATA_EXPIRES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ENDS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.study.StudyLimits
import java.sql.Connection
import java.util.*

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public open class StudyLimitsService(
    private val storageResolver: StorageResolver,
    hazelcast: HazelcastInstance
) : StudyLimitsManager {
    internal companion object {
        private val mapper = ObjectMappers.newJsonMapper()

        private val STUDY_LIMITS_COLS = STUDY_LIMITS.columns.joinToString(",") { it.name }
        /**
         * 1. STUDY_ID
         * 2. STUDY_DURATION
         * 3. DATA_RETENTION
         * 4. STUDY_ENDS
         * 5. STUDY_DATA_EXPIRES
         */
        private val INSERT_STUDY_LIMITS = """
            INSERT INTO ${STUDY_LIMITS.name}($STUDY_LIMITS_COLS) VALUES(?,?::jsonb,?::jsonb,?,?)
        """.trimIndent()
        private val UPSERT_STUDY_LIMITS = INSERT_STUDY_LIMITS + " ON CONFLICT (${STUDY_ID.name}) DO UPDATE SET " +
            STUDY_LIMITS.columns.drop(1).joinToString(",") { "${it.name} = EXCLUDED.${it.name}" }

        // A NULL end (no end set by the researcher) never compares <= now(), so open-ended studies run on.
        private val STUDIES_EXCEEDING_DURATION_LIMIT = """
            SELECT * FROM ${STUDIES.name} INNER JOIN ${STUDY_LIMITS.name} USING (${STUDY_ID.name}) 
            WHERE ${STUDY_ENDS.name} <= now()
        """.trimIndent()
        private val STUDIES_EXCEEDING_RETENTION_LIMIT = """
            SELECT * FROM ${STUDIES.name} INNER JOIN ${STUDY_LIMITS.name} USING (${STUDY_ID.name}) 
            WHERE ${DATA_EXPIRES.name} <= now()
        """.trimIndent()
        private val COUNT_STUDY_PARTICIPANTS_SQL = """
            SELECT ${STUDY_ID.name}, count(*) FROM ${ChroniclePostgresTables.STUDY_PARTICIPANTS.name} WHERE ${STUDY_ID.name} = ANY(?)
            GROUP BY ${STUDY_ID.name}
        """.trimIndent()
    }

    private val studyLimits = HazelcastMap.STUDY_LIMITS.getMap(hazelcast)

    override fun initializeStudyLimits(connection: Connection, studyId: UUID, studyLimits: StudyLimits): Unit =
        store(connection, studyId, studyLimits, INSERT_STUDY_LIMITS)

    private fun store(connection: Connection, studyId: UUID, studyLimits: StudyLimits, sql: String) {
        connection.prepareStatement(sql).use { ps ->
            ps.setObject(1, studyId)
            ps.setString(2, studyLimits.studyDuration?.let(mapper::writeValueAsString))
            ps.setString(3, mapper.writeValueAsString(studyLimits.dataRetentionDuration))
            ps.setObject(4, studyLimits.studyEnds)
            ps.setObject(5, studyLimits.studyDataExpires)
            ps.executeUpdate()
        }
    }

    override fun setStudyLimits(connection: Connection, studyId: UUID, studyLimits: StudyLimits) {
        store(connection, studyId, studyLimits, UPSERT_STUDY_LIMITS)
        PinnedPlatformConnection.afterCommit(storageResolver.getPlatformStorage(), StudyLimitsService::class.java to studyId) {
            this.studyLimits.evict(studyId)
        }
    }

    override fun getStudyLimits(studyId: UUID): StudyLimits {
        return studyLimits.getValue(studyId)
    }

    override fun getStudiesExceedingDurationLimit(): Set<UUID> {
        return BasePostgresIterable(
            PreparedStatementHolderSupplier(
                storageResolver.getPlatformStorage(),
                STUDIES_EXCEEDING_DURATION_LIMIT
            ) { }
        ) { ResultSetAdapters.studyId(it) }.toSet()
    }

    override fun getStudiesExcceedingDataRetentionPeriod(): Set<UUID> {
        return BasePostgresIterable(
            PreparedStatementHolderSupplier(
                storageResolver.getPlatformStorage(),
                STUDIES_EXCEEDING_RETENTION_LIMIT
            ) { }
        ) { ResultSetAdapters.studyId(it) }.toSet()
    }

    override fun countStudyParticipants(studyId: UUID): Long {
        val hds = storageResolver.getPlatformStorage()
        return hds.connection.use { connection ->
            countStudyParticipants(connection, setOf(studyId))[studyId] ?: 0L
        }
    }

    override fun countStudyParticipants(studyIds: Set<UUID>): Map<UUID, Long> {
        val hds = storageResolver.getPlatformStorage()
        return hds.connection.use { connection ->
            countStudyParticipants(connection, studyIds)
        }
    }

    override fun countStudyParticipants(connection: Connection, studyIds: Set<UUID>): Map<UUID, Long> {
        val studyCounts = mutableMapOf<UUID, Long>()
        connection.prepareStatement(COUNT_STUDY_PARTICIPANTS_SQL).use { ps ->
            ps.setArray(1, PostgresArrays.createUuidArray(connection, studyIds))
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    studyCounts[ResultSetAdapters.studyId(rs)] = ResultSetAdapters.count(rs)
                }
            }
        }
        return studyCounts
    }
}

/** Call after locking the study row, so a concurrent settings edit cannot change the deadline. */
internal fun studyHasExpired(connection: Connection, studyId: UUID): Boolean = connection.prepareStatement(
    "SELECT ${STUDY_ENDS.name} <= now() FROM ${STUDY_LIMITS.name} WHERE ${STUDY_ID.name} = ?",
).use { statement ->
    statement.setObject(1, studyId)
    statement.executeQuery().use { it.next() && it.getBoolean(1) }
}
