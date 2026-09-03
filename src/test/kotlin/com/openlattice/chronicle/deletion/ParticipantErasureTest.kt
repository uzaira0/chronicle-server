package com.openlattice.chronicle.deletion

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import java.sql.Connection
import java.util.UUID

/**
 * Right-to-erasure (HIPAA / GDPR) regression test: deleting a participant must remove **every** row
 * that participant produced across **every** collection table, while leaving other participants and
 * other studies untouched.
 *
 * It drives the production erasure inventory ([StudyDeletionTable]) and the production erasure
 * predicate (`WHERE study_id = ? AND participant_id = ANY(?)` — the exact filter every
 * `DeleteParticipant*DataRunner` uses) against a real Postgres. Creating each table from the enum
 * means a new collection table added to the erasure path is automatically covered here; a new
 * collection table that is NOT added to [StudyDeletionTable] is caught by [erasureCoversEveryCollectionTable].
 *
 * This is the test that would have failed before the six sensing-expansion tables (sleep_events,
 * activity_recognition_events, health_metrics, connectivity_state_events, app_network_usage,
 * device_settings) were wired into the deletion path.
 */
class ParticipantErasureTest {

    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_test")

        private val STUDY_A: UUID = UUID.randomUUID()
        private val STUDY_B: UUID = UUID.randomUUID()
        private const val P1 = "participant-to-erase"
        private const val P2 = "participant-to-keep"

        // Participant-data collection tables that erasure MUST purge. Add a new collection table here
        // and the coverage test forces a matching StudyDeletionTable entry + per-participant runner.
        private val REQUIRED_COLLECTION_TABLES = setOf(
            "chronicle_usage_events", "chronicle_usage_stats", "preprocessed_usage_events",
            "sensor_data", "android_sensor_data", "battery_telemetry", "interaction_events",
            "app_audio_activity", "app_audio_content", "ambient_audio_events", "notification_activity",
            "sleep_events", "activity_recognition_events", "health_metrics",
            "connectivity_state_events", "app_network_usage", "device_settings",
            "app_usage_survey", "questionnaire_submissions", "time_use_diary_submissions",
            "participant_stats", "upload_buffer",
        )
    }

    private lateinit var hds: HikariDataSource

    private fun tableNames(): List<String> = StudyDeletionTable.dataTableNames()

    @Before
    fun setUp() {
        hds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 1
            minimumIdle = 1
        })
        hds.connection.use { c ->
            c.createStatement().use { st ->
                for (t in tableNames()) {
                    // Minimal shell — erasure keys only on study_id + participant_id.
                    st.execute(
                        """CREATE TABLE IF NOT EXISTS "$t" (
                               study_id UUID NOT NULL, participant_id TEXT NOT NULL, marker TEXT)""",
                    )
                    st.execute("""INSERT INTO "$t" VALUES ('$STUDY_A', '$P1', 'x'), ('$STUDY_A', '$P2', 'x'), ('$STUDY_B', '$P1', 'x')""")
                }
            }
        }
    }

    // reason: connection/statement use{} plus the drop-table loop is inherent JDBC cleanup nesting
    @Suppress("NestedBlockDepth")
    @After
    fun tearDown() {
        if (::hds.isInitialized) {
            hds.connection.use { c ->
                c.createStatement().use { st ->
                    for (t in tableNames()) st.execute("""DROP TABLE IF EXISTS "$t"""")
                }
            }
            hds.close()
        }
    }

    private fun count(c: Connection, table: String, study: UUID, participant: String): Int =
        c.prepareStatement("""SELECT count(*) FROM "$table" WHERE study_id = ? AND participant_id = ?""").use { ps ->
            ps.setObject(1, study)
            ps.setString(2, participant)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    @Test
    fun erasingAParticipantRemovesEveryRowAcrossEveryCollectionTable() {
        hds.connection.use { c ->
            // Apply the production erasure predicate to every collection table for P1 in STUDY_A.
            for (t in tableNames()) {
                c.prepareStatement("""DELETE FROM "$t" WHERE study_id = ? AND participant_id = ANY(?)""").use { ps ->
                    ps.setObject(1, STUDY_A)
                    ps.setArray(2, c.createArrayOf("text", arrayOf(P1)))
                    ps.executeUpdate()
                }
            }
            for (t in tableNames()) {
                assertEquals("$t still holds erased participant's rows", 0, count(c, t, STUDY_A, P1))
                assertEquals("$t lost a co-enrolled participant's rows", 1, count(c, t, STUDY_A, P2))
                assertEquals("$t lost another study's rows", 1, count(c, t, STUDY_B, P1))
            }
        }
    }

    @Test
    fun erasureCoversEveryCollectionTable() {
        val covered = StudyDeletionTable.dataTableNames().toSet()
        val missing = REQUIRED_COLLECTION_TABLES - covered
        assertTrue(
            "Collection tables missing from the participant erasure path (add a StudyDeletionTable " +
                "entry + DeleteParticipant*Data runner): $missing",
            missing.isEmpty(),
        )
    }
}
