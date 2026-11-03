package com.openlattice.chronicle.services.studies

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.ParticipantAnnotationsUpdate
import com.openlattice.chronicle.storage.DeletionStudyFence
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPANT_NOTES
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPANT_TAGS
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UPDATED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.STUDY_PARTICIPANTS
import java.sql.Connection
import java.util.UUID

internal class StudySnapshotRepository(private val connection: Connection) {
    companion object {
        /**
         * PreparedStatement bind order
         * 1) notes present, 2) participantNotes
         * 3) tags present, 4) participantTags (text array)
         * 5) studyId, 6) participantId
         */
        private val SET_PARTICIPANT_ANNOTATIONS_SQL = """
            UPDATE ${STUDY_PARTICIPANTS.name}
            SET ${PARTICIPANT_NOTES.name} = CASE WHEN ? THEN ? ELSE ${PARTICIPANT_NOTES.name} END,
                ${PARTICIPANT_TAGS.name} = CASE WHEN ? THEN ?::text[] ELSE ${PARTICIPANT_TAGS.name} END,
                ${UPDATED_AT.name} = now()
            WHERE ${STUDY_ID.name} = ? AND ${PARTICIPANT_ID.name} = ?
        """.trimIndent()

        internal const val PARTICIPANT_NOTES_KEY = "participantNotes"
        internal const val PARTICIPANT_TAGS_KEY = "participantTags"
    }

    fun settingsRevision(studyId: UUID): Long = connection.prepareStatement(
        "SELECT settings_revision FROM studies WHERE study_id = ?",
    ).use { statement ->
        statement.setObject(1, studyId)
        statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
    }

    fun bumpSettingsRevision(studyId: UUID): Long = connection.prepareStatement(
        "UPDATE studies SET settings_revision = settings_revision + 1 WHERE study_id = ? RETURNING settings_revision",
    ).use { statement ->
        statement.setObject(1, studyId)
        statement.executeQuery().use { rows ->
            check(rows.next()) { "No study with id $studyId" }
            rows.getLong(1)
        }
    }

    fun loadLockedStudySettings(studyId: UUID): StudySettings? = connection.prepareStatement(
        "SELECT settings FROM studies WHERE study_id = ? FOR UPDATE",
    ).use { statement ->
        statement.setObject(1, studyId)
        statement.executeQuery().use { rows ->
            if (!rows.next()) return@use null
            rows.getString("settings")?.let { ObjectMappers.getJsonMapper().readValue(it, StudySettings::class.java) }
                ?: StudySettings(emptyMap())
        }
    }

    /** The caller holds the studies row lock, shared with manifest validation and every settings writer. */
    fun loadLockedStudyFeatures(studyId: UUID): Set<String> =
        connection.prepareStatement("SELECT modules FROM studies WHERE study_id = ? FOR UPDATE").use { statement ->
            statement.setObject(1, studyId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Study is unavailable" }
                val json = rows.getString("modules") ?: "{}"
                ObjectMappers.getJsonMapper().readTree(json).fieldNames().asSequence().toSet()
            }
        }

    fun loadLockedParticipantAnnotations(studyId: UUID, participantId: String): String? {
        DeletionStudyFence.shared(connection, studyId)
        return connection.prepareStatement(
            "SELECT jsonb_build_object('$PARTICIPANT_NOTES_KEY', participant_notes, '$PARTICIPANT_TAGS_KEY', participant_tags)::text " +
                "FROM study_participants WHERE study_id = ? AND participant_id = ? FOR UPDATE"
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, participantId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }
    }

    fun updateParticipantAnnotations(studyId: UUID, participantId: String, annotations: ParticipantAnnotationsUpdate) {
        val notes = annotations.participantNotes?.orElse(null)
        val tags = annotations.participantTags?.orElse(null)?.toTypedArray() ?: emptyArray()

        DeletionStudyFence.shared(connection, studyId)
        connection.prepareStatement(SET_PARTICIPANT_ANNOTATIONS_SQL).use { ps ->
            ps.setBoolean(1, annotations.participantNotes != null)
            ps.setString(2, notes)
            ps.setBoolean(3, annotations.participantTags != null)
            ps.setArray(4, connection.createArrayOf("text", tags))
            ps.setObject(5, studyId)
            ps.setString(6, participantId)
            ps.executeUpdate()
        }
    }
}
