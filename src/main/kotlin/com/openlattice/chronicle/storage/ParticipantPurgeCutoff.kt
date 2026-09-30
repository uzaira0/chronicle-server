package com.openlattice.chronicle.storage

import com.geekbeast.mappers.mappers.ObjectMappers
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID

/** Batch admission and buffer materialization share the same durable collection watermark. */
internal object ParticipantPurgeCutoff {
    data class Subject(val studyId: UUID, val participantId: String)
    private val mapper = ObjectMappers.newJsonMapper()

    fun load(connection: Connection, subjects: Collection<Subject>): Map<Subject, OffsetDateTime> {
        if (subjects.isEmpty()) return emptyMap()
        return connection.prepareStatement(
            """
            SELECT subject.study_id, subject.participant_id, erased.cutoff
            FROM jsonb_to_recordset(?::jsonb) AS subject(study_id uuid, participant_id text)
            JOIN participant_purge_cutoffs erased ON erased.study_id = subject.study_id
              AND erased.participant_block_token = md5(subject.study_id::text || ':' || subject.participant_id)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, mapper.writeValueAsString(subjects.distinct().map {
                mapOf("study_id" to it.studyId, "participant_id" to it.participantId)
            }))
            statement.executeQuery().use { rows -> buildMap {
                while (rows.next()) put(
                    Subject(rows.getObject(1, UUID::class.java), rows.getString(2)),
                    rows.getObject(3, OffsetDateTime::class.java),
                )
            } }
        }
    }

    fun load(connection: Connection, studyId: UUID, participantId: String): OffsetDateTime? =
        load(connection, listOf(Subject(studyId, participantId)))[Subject(studyId, participantId)]

    // Accepted limitation: device clock skew can admit erased samples from a fast clock or
    // discard fresh samples from a slow clock. This release uses observation-time cutoffs,
    // without record fingerprints or collection generations.
    // An undated payload cannot prove it was collected after a purge. Receipt time is never a substitute.
    fun permits(cutoff: OffsetDateTime?, collectedAt: OffsetDateTime?): Boolean =
        cutoff == null || (collectedAt != null && !collectedAt.isBefore(cutoff))

    fun <T, R> withPermittedBatch(
        dataSource: HikariDataSource,
        studyId: UUID,
        participantId: String,
        data: List<T>,
        collectedAt: (T) -> OffsetDateTime?,
        persist: (Connection, List<T>) -> R,
    ): R = dataSource.connection.use { connection ->
        val ownsTransaction = connection.autoCommit
        if (ownsTransaction) connection.autoCommit = false
        try {
            DeletionStudyFence.shared(connection, studyId)
            val cutoff = load(connection, studyId, participantId)
            val result = persist(connection, data.filter { permits(cutoff, collectedAt(it)) })
            if (ownsTransaction) connection.commit()
            result
        } catch (failure: Exception) {
            if (ownsTransaction) connection.rollback()
            throw failure
        } finally {
            if (ownsTransaction) connection.autoCommit = true
        }
    }
}
