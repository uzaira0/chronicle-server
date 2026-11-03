package com.openlattice.chronicle.upgrades

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.contract.seedEnrolledParticipant
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.StudySettings
import java.util.UUID

internal class MigrationStudyFixture(private val storageResolver: StorageResolver) {
    fun ensureStudyParticipant(studyId: UUID, participantId: String) {
        storageResolver.getBootstrapStorage().connection.use { connection ->
            connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, 'collection fixture') ON CONFLICT DO NOTHING").use {
                it.setObject(1, studyId)
                it.executeUpdate()
            }
            seedEnrolledParticipant(connection, studyId, participantId)
        }
    }

    fun insertStudySettings(studyId: UUID, title: String, settings: StudySettings, collectionEnabled: Boolean = false) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            executeSingleUpdate(
                connection,
                "INSERT INTO studies (study_id, title, settings, modules) VALUES (?, ?, ?::jsonb, ?::jsonb)",
            ) { statement ->
                statement.setObject(1, studyId)
                statement.setString(2, title)
                statement.setString(3, ObjectMappers.getJsonMapper().writeValueAsString(settings))
                statement.setString(4, if (collectionEnabled) """{"CHRONICLE_DATA_COLLECTION":{}}""" else "{}")
            }
        }
    }

    fun deleteStudyRow(studyId: UUID) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            executeSingleUpdate(connection, "DELETE FROM studies WHERE study_id = ?", expected = null) {
                it.setObject(1, studyId)
            }
        }
    }
}
