package com.openlattice.chronicle.storage.rls

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

/** Fresh-schema audit immutability and study isolation, including hostile blanket grants. */
class AuditTrailImmutabilityRlsTest {

    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_test")

        private val STUDY_A = UUID.randomUUID()
        private val STUDY_B = UUID.randomUUID()


    }

    private lateinit var hds: HikariDataSource

    @Before
    fun setUp() {
        hds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 1
            minimumIdle = 1
        })

        ChronicleContractTestSchema.resetSchemaAndMigrations(postgres)
        hds.connection.use { c ->
            c.createStatement().use { st ->
                // Reproduce the prod condition: the role HAS the full blanket grant that defeated the
                // REVOKE. If immutability were REVOKE-based this would re-open UPDATE/DELETE.
                st.execute("CREATE ROLE $RLS_TEST_APP_ROLE NOSUPERUSER NOBYPASSRLS")
                st.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO $RLS_TEST_APP_ROLE")
                st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON study_settings_audit TO $RLS_TEST_APP_ROLE")
                st.execute(
                    "GRANT SELECT, INSERT, UPDATE, DELETE ON " +
                        "participant_collection_acknowledgment TO $RLS_TEST_APP_ROLE",
                )
                st.execute(
                    "GRANT SELECT, INSERT, UPDATE, DELETE ON " +
                        "mobile_withdrawal_requests TO $RLS_TEST_APP_ROLE",
                )

                // Seed as superuser (bypasses RLS): one row per study in each table.
                for ((study, participant) in listOf(STUDY_A to "p-a", STUDY_B to "p-b")) {
                    appendSetting(c, study)
                    appendAcknowledgment(c, study, participant)
                }
                st.execute(
                    """
                    INSERT INTO mobile_withdrawal_requests
                        (request_id, api_key_id, study_id, participant_id, random_device_id, already_withdrawn)
                    VALUES
                        (gen_random_uuid(), gen_random_uuid(), '$STUDY_A', 'p-a', gen_random_uuid(), false),
                        (gen_random_uuid(), gen_random_uuid(), '$STUDY_B', 'p-b', gen_random_uuid(), true)
                    """.trimIndent(),
                )
            }
        }
    }

    @After
    fun tearDown() {
        if (::hds.isInitialized) {
            ChronicleContractTestSchema.clearSchema(postgres)
            hds.connection.use { c ->
                c.createStatement().use { st ->
                    st.execute("DROP ROLE IF EXISTS $RLS_TEST_APP_ROLE")
                }
            }
            hds.close()
        }
    }

    /** Run [block] under the app role + the given RLS context inside a rolled-back transaction. */
    private fun <T> inContext(authorizedStudies: String, isAdmin: Boolean, block: (Connection) -> T): T {
        hds.connection.use { c ->
            c.autoCommit = false
            try {
                c.applyLocalRlsTestContext(isAdmin, authorizedStudies)
                return block(c)
            } finally {
                c.rollback()
                c.autoCommit = true
            }
        }
    }

    private fun appendSetting(connection: Connection, studyId: UUID): Int =
        connection.prepareStatement("""
            INSERT INTO study_settings_audit (id, study_id, changed_by, setting_key, after_value, change_summary)
            VALUES (gen_random_uuid(), ?, 'fixture', 'fixture', '{}', 'fixture')
        """.trimIndent()).use {
            it.setObject(1, studyId)
            it.executeUpdate()
        }

    private fun appendAcknowledgment(connection: Connection, studyId: UUID, participantId: String): Int =
        connection.prepareStatement("""
            INSERT INTO participant_collection_acknowledgment
                (id, study_id, participant_id, source_random_device_id, acknowledged_modules, acknowledged_at, decision_seq)
            VALUES (gen_random_uuid(), ?, ?, 'fixture-device', '[]', now(), 1)
        """.trimIndent()).use {
            it.setObject(1, studyId)
            it.setString(2, participantId)
            it.executeUpdate()
        }

    private fun exec(c: Connection, sql: String): Int = c.createStatement().use { it.executeUpdate(sql) }

    private fun count(c: Connection, table: String): Int =
        c.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM $table").use { rs -> rs.next(); rs.getInt(1) }
        }

    @Test
    fun `study_settings_audit UPDATE is blocked despite a granted UPDATE privilege`() {
        val updated = inContext(STUDY_A.toString(), isAdmin = false) {
            exec(it, "UPDATE study_settings_audit SET change_summary = 'tampered' WHERE study_id = '$STUDY_A'")
        }
        assertEquals("RLS must block UPDATE on study_settings_audit even with UPDATE granted", 0, updated)
    }

    @Test
    fun `study_settings_audit DELETE is blocked despite a granted DELETE privilege`() {
        val deleted = inContext(STUDY_A.toString(), isAdmin = false) {
            exec(it, "DELETE FROM study_settings_audit WHERE study_id = '$STUDY_A'")
        }
        assertEquals("RLS must block DELETE on study_settings_audit even with DELETE granted", 0, deleted)
    }

    @Test
    fun `participant_collection_acknowledgment UPDATE and DELETE are blocked despite granted privileges`() {
        val updated = inContext(STUDY_A.toString(), isAdmin = false) {
            exec(it, "UPDATE participant_collection_acknowledgment SET participant_id = 'x' WHERE study_id = '$STUDY_A'")
        }
        val deleted = inContext(STUDY_A.toString(), isAdmin = false) {
            exec(it, "DELETE FROM participant_collection_acknowledgment WHERE study_id = '$STUDY_A'")
        }
        assertEquals("RLS must block UPDATE on the consent trail even with UPDATE granted", 0, updated)
        assertEquals("RLS must block DELETE on the consent trail even with DELETE granted", 0, deleted)
    }

    @Test
    fun `mobile withdrawal receipt UPDATE and DELETE are blocked after blanket grants`() {
        val updated = inContext(STUDY_A.toString(), isAdmin = false) {
            exec(
                it,
                "UPDATE mobile_withdrawal_requests SET already_withdrawn = true WHERE study_id = '$STUDY_A'",
            )
        }
        val deleted = inContext(STUDY_A.toString(), isAdmin = false) {
            exec(it, "DELETE FROM mobile_withdrawal_requests WHERE study_id = '$STUDY_A'")
        }

        assertEquals("RLS must block withdrawal receipt UPDATE even with UPDATE granted", 0, updated)
        assertEquals("RLS must block withdrawal receipt DELETE even with DELETE granted", 0, deleted)
    }

    @Test
    fun `INSERT into both trails still works for the app role`() {
        val a = inContext(STUDY_A.toString(), isAdmin = false) {
            appendSetting(it, STUDY_A)
        }
        val b = inContext(STUDY_A.toString(), isAdmin = false) {
            appendAcknowledgment(it, STUDY_A, "p2")
        }
        assertEquals("append (INSERT) must remain allowed on study_settings_audit", 1, a)
        assertEquals("append (INSERT) must remain allowed on the consent trail", 1, b)
        val withdrawal = inContext(STUDY_A.toString(), isAdmin = false) {
            exec(
                it,
                """
                INSERT INTO mobile_withdrawal_requests
                    (request_id, api_key_id, study_id, participant_id, random_device_id, already_withdrawn)
                VALUES (gen_random_uuid(), gen_random_uuid(), '$STUDY_A', 'p-new', gen_random_uuid(), false)
                """.trimIndent(),
            )
        }
        assertEquals("the request-path app role must still append withdrawal receipts", 1, withdrawal)
    }

    @Test
    fun `BYPASSRLS admin cannot forge a withdrawal receipt`() {
        hds.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute("SET LOCAL ROLE chronicle_admin") }
                org.junit.Assert.assertThrows(java.sql.SQLException::class.java) {
                    exec(
                        connection,
                        """
                        INSERT INTO mobile_withdrawal_requests
                            (request_id, api_key_id, study_id, participant_id, random_device_id, already_withdrawn)
                        VALUES (gen_random_uuid(), gen_random_uuid(), '$STUDY_A', 'forged', gen_random_uuid(), false)
                        """.trimIndent(),
                    )
                }
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
        }
    }

    @Test
    fun `authorized SELECT sees the study row`() {
        val visible = inContext(STUDY_A.toString(), isAdmin = false) { count(it, "study_settings_audit") }
        assertTrue("an authorized principal must still read its study's audit rows", visible >= 1)
    }

    @Test
    fun `study isolation - empty context sees nothing`() {
        val visible = inContext(authorizedStudies = "", isAdmin = false) { count(it, "study_settings_audit") }
        assertEquals("no context must expose no audit rows", 0, visible)
    }

    @Test
    fun `study isolation - a different study cannot read another study's trail`() {
        val visible = inContext(STUDY_B.toString(), isAdmin = false) {
            it.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM study_settings_audit WHERE study_id = '$STUDY_A'").use { rs ->
                    rs.next(); rs.getInt(1)
                }
            }
        }
        assertEquals("a principal authorized only for STUDY_B must not see STUDY_A audit rows", 0, visible)
    }

    @Test
    fun `superuser bypasses RLS so retention purges remain possible (control)`() {
        // The pool authenticates as the superuser; with no SET ROLE it bypasses RLS entirely.
        hds.connection.use { c ->
            c.autoCommit = false
            try {
                val deleted = exec(c, "DELETE FROM study_settings_audit WHERE study_id = '$STUDY_A'")
                assertEquals("the superuser must still be able to purge for retention", 1, deleted)
            } finally {
                c.rollback()
                c.autoCommit = true
            }
        }
    }
}
