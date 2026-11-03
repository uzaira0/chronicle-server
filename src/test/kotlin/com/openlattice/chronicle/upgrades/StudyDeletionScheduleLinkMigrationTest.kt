package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import java.sql.SQLException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StudyDeletionScheduleLinkMigrationTest {
    @Test
    fun freshSchedulesRequireTheirOperationAndRestorableStatus() {
        ChronicleContractTestSchema.sharedPostgres.createConnection("").use { connection ->
            val studyId = UUID.randomUUID()
            connection.autoCommit = false
            try {
                connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, 'schedule fixture')").use {
                    it.setObject(1, studyId)
                    it.executeUpdate()
                }
                val savepoint = connection.setSavepoint()
                val failure = assertThrows(SQLException::class.java) {
                    connection.prepareStatement("""
                        INSERT INTO study_deletion_schedule (study_id, scheduled_by, delete_after, previous_status)
                        VALUES (?, 'fixture', now(), 'ACTIVE')
                    """.trimIndent()).use {
                        it.setObject(1, studyId)
                        it.executeUpdate()
                    }
                }
                assertEquals("23502", failure.sqlState)
                connection.rollback(savepoint)
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
        }
    }
}
