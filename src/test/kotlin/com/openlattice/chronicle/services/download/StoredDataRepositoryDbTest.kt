package com.openlattice.chronicle.services.download

import com.openlattice.chronicle.contract.seedCandidate

import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.services.delete.RestoreContinuityFixture
import com.openlattice.chronicle.services.delete.RestoreContinuityFixture.Companion.storageResolver
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.PostgresEventTables
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.upgrades.MigrationStudyFixture
import com.openlattice.chronicle.upgrades.deleteById
import com.openlattice.chronicle.upgrades.executeSingleUpdate
import com.openlattice.chronicle.util.tests.TestDataFactory
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.UUID

class StoredDataRepositoryDbTest {
    companion object {
        @BeforeClass @JvmStatic fun start() = RestoreContinuityFixture.start()
        @AfterClass @JvmStatic fun stop() = RestoreContinuityFixture.stop()
    }

    /**
     * Export availability asks whether a study stored any rows of a type: only that study's rows answer, in the
     * platform store for platform tables and in event storage (text study ids) for usage events.
     */
    @Test
    fun storedRowsAreFoundOnlyForTheirOwnStudyInTheirOwnStore() = RLSRequestContext.withSystemContext {
        val studyFixture = MigrationStudyFixture(storageResolver)
        val withRows = UUID.randomUUID()
        val withoutRows = UUID.randomUUID()
        val settings = StudySettings(mapOf(
            StudySettingType.ParticipantPolicy to TestDataFactory.participantPolicy(),
            StudySettingType.DataCollection to AndroidDataCollectionSetting(settingsVersion = 1),
        ))
        listOf(withRows, withoutRows).forEach { studyFixture.insertStudySettings(it, "Export $it", settings) }
        try {
            val repository = StoredDataRepository(storageResolver)
            assertFalse(repository.hasRows(withRows, ChroniclePostgresTables.STUDY_PARTICIPANTS))
            storageResolver.getPlatformStorage().connection.use { connection ->
                executeSingleUpdate(connection, """INSERT INTO study_participants
                    (study_id, participant_id, candidate_id, participation_status) VALUES (?, ?, ?, ?)""".trimIndent()) {
                    it.setObject(1, withRows)
                    it.setString(2, "export-participant")
                    it.setObject(3, seedCandidate(connection))
                    it.setString(4, ParticipationStatus.ENROLLED.name)
                }
            }

            assertTrue(repository.hasRows(withRows, ChroniclePostgresTables.STUDY_PARTICIPANTS))
            assertFalse(repository.hasRows(withoutRows, ChroniclePostgresTables.STUDY_PARTICIPANTS))
            assertFalse(repository.hasRows(withRows, PostgresEventTables.CHRONICLE_USAGE_EVENTS, eventStorage = true))
        } finally {
            storageResolver.getPlatformStorage().connection.use { deleteById(it, "study_participants", "study_id", withRows) }
            listOf(withRows, withoutRows).forEach(studyFixture::deleteStudyRow)
        }
    }
}
