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
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DATA_RETENTION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.FEATURES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPANT_LIMIT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_DURATION
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ENDS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.StudyDuration
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.study.StudyLimits
import java.sql.Connection
import java.sql.PreparedStatement
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
         * 2. PARTICIPANT_LIMIT
         * 3. STUDY_DURATION
         * 4. DATA_RETENTION
         * 5. STUDY_EXPIRES
         * 6. STUDY_DATA_EXPIRES
         * 7. FEATURES
         */
        private val INSERT_STUDY_LIMITS = """
            INSERT INTO ${STUDY_LIMITS.name}($STUDY_LIMITS_COLS) VALUES(?,?,?::jsonb,?::jsonb,?,?,?) 
        """.trimIndent()
        private val LOCK_STUDY = """
            SELECT ${PARTICIPANT_LIMIT.name} FROM ${STUDY_LIMITS.name} WHERE ${STUDY_ID.name} = ? FOR UPDATE
         """.trimIndent()

        private val UPDATE_PARTICIPANT_LIMIT = """
            UPDATE ${STUDY_LIMITS.name} SET ${PARTICIPANT_LIMIT.name} = ? WHERE ${STUDY_ID.name} = ?
         """.trimIndent()

        private val UPDATE_STUDY_DURATION = """
            UPDATE ${STUDY_LIMITS.name} SET ${STUDY_DURATION.name} = ?::jsonb WHERE ${STUDY_ID.name} = ?
         """.trimIndent()

        private val UPDATE_RETENTION_PERIOD = """
            UPDATE ${STUDY_LIMITS.name} SET ${DATA_RETENTION.name} = ?::jsonb WHERE ${STUDY_ID.name} = ?
         """.trimIndent()

        private val UPDATE_STUDY_FEATURES = """
            UPDATE ${STUDY_LIMITS.name} SET ${FEATURES.name} = ? WHERE ${STUDY_ID.name} = ?
         """.trimIndent()

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

    override fun initializeStudyLimits(connection: Connection, studyId: UUID, studyLimits: StudyLimits) {
        connection.prepareStatement(INSERT_STUDY_LIMITS).use { ps ->
            ps.setObject(1, studyId)
            ps.setInt(2, studyLimits.participantLimit)
            ps.setString(3, mapper.writeValueAsString(studyLimits.studyDuration))
            ps.setString(4, mapper.writeValueAsString(studyLimits.dataRetentionDuration))
            ps.setObject(5, studyLimits.studyEnds)
            ps.setObject(6, studyLimits.studyDataExpires)
            ps.setArray(7, PostgresArrays.createTextArray(ps.connection, studyLimits.features.map { it.name }))
            ps.executeUpdate()
        }
    }

    override fun lockStudyForEnrollments(connection: Connection, studyId: UUID): Int {
        return connection.prepareStatement(LOCK_STUDY).use { ps ->
            ps.setObject(1, studyId)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "Study limits do not exist for study $studyId" }
                rs.getInt(PARTICIPANT_LIMIT.name)
            }
        }
    }

    override fun getEnrollmentCapacity(studyId: UUID): Int {
        return studyLimits.getValue(studyId).participantLimit
    }

    override fun setEnrollmentCapacity(studyId: UUID, capacity: Int) {
        require(capacity > 0) { "Enrollment capacity must be positive" }
        updateSingleStudyLimit(studyId, UPDATE_PARTICIPANT_LIMIT) { ps ->
            ps.setInt(1, capacity)
        }
    }

    override fun setStudyDuration(studyId: UUID, studyDuration: StudyDuration) {
        updateSingleStudyLimit(studyId, UPDATE_STUDY_DURATION) { ps ->
            ps.setString(1, mapper.writeValueAsString(studyDuration))
        }
    }

    override fun getStudyDuration(studyId: UUID): StudyDuration {
        return studyLimits.getValue(studyId).studyDuration
    }

    override fun setDataRetentionPeriod(studyId: UUID, dataRetentionPeriod: StudyDuration) {
        updateSingleStudyLimit(studyId, UPDATE_RETENTION_PERIOD) { ps ->
            ps.setString(1, mapper.writeValueAsString(dataRetentionPeriod))
        }
    }

    override fun getDataRetentionPeriod(studyId: UUID): StudyDuration {
        return studyLimits.getValue(studyId).dataRetentionDuration
    }

    override fun getStudyFeatures(studyId: UUID): Set<StudyFeature> {
        return studyLimits.getValue(studyId).features
    }

    override fun setStudyFeatures(studyId: UUID, studyFeatures: Set<StudyFeature>) {
        updateSingleStudyLimit(studyId, UPDATE_STUDY_FEATURES) { ps ->
            ps.setArray(1, PostgresArrays.createTextArray(ps.connection, studyFeatures.map { it.name }))
        }
    }

    override fun setStudyLimits(studyId: UUID, studyLimits: StudyLimits) {
        this.studyLimits[studyId] = studyLimits
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

    private fun updateSingleStudyLimit(
        studyId: UUID,
        sql: String,
        bindValue: (PreparedStatement) -> Unit,
    ) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(sql).use { ps ->
                bindValue(ps)
                ps.setObject(2, studyId)
                check(ps.executeUpdate() == 1) { "Study limits do not exist for study $studyId" }
            }
        }
        studyLimits.loadAll(setOf(studyId), true)
    }
}
