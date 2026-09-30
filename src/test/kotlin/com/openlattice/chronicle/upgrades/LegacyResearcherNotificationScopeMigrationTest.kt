package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import java.util.UUID

class LegacyResearcherNotificationScopeMigrationTest {
    companion object {
        @ClassRule @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_legacy_notification_scope")
    }

    @Test
    fun `upgrade normalizes only absent notification participants before V74 backfill`() {
        postgres.createConnection("").use(ChronicleContractTestSchema::applyFrameworkSchema)
        FlywayMigrationService.baseConfiguration()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password).target("73").load().migrate()
        val studyId = UUID.randomUUID()
        val blankJob = UUID.randomUUID()
        val participantJob = UUID.randomUUID()
        postgres.createConnection("").use { connection ->
            connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, 'legacy notifications')").use {
                it.setObject(1, studyId)
                it.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO jobs (job_id, securable_principal_id, principal_type, principal_id, status, contact, " +
                    "definition, message, deleted_rows) VALUES (?, ?, 'USER', 'researcher', 'PENDING', '', ?::jsonb, '', 0)",
            ).use {
                listOf(blankJob to "", participantJob to "participant-retained").forEach { (jobId, participant) ->
                    it.setObject(1, jobId)
                    it.setObject(2, UUID.randomUUID())
                    it.setString(3, """{"@type":"com.openlattice.chronicle.services.notifications.Notification","studyId":"$studyId","participantId":"$participant"}""")
                    it.addBatch()
                }
                it.executeBatch()
            }
        }
        assertTrue(ChronicleContractTestSchema.migrate(postgres).success)
        postgres.createConnection("").use { connection ->
            connection.prepareStatement("SELECT jsonb_exists(definition, 'participantId'), participant_ids FROM jobs WHERE job_id = ?").use {
                it.setObject(1, blankJob)
                it.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(false, rows.getBoolean(1))
                    assertEquals(0, (rows.getArray(2).array as Array<*>).size)
                }
                it.setObject(1, participantJob)
                it.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertTrue(rows.getBoolean(1))
                    assertEquals(listOf("participant-retained"), (rows.getArray(2).array as Array<*>).toList())
                }
            }
        }
        assertEquals(0, ChronicleContractTestSchema.migrate(postgres).migrationsExecuted)
    }
}
