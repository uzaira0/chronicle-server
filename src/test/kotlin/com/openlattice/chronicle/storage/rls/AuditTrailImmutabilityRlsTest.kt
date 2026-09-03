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

/**
 * Regression test for append-only audit and withdrawal receipts under blanket application grants.
 *
 * `study_settings_audit` and `participant_collection_acknowledgment` were immutable in INTENT
 * (V25 / V26 `REVOKE DELETE, UPDATE ... FROM chronicle_app`) but MUTABLE in fact: the idempotent
 * blanket `GRANT ... ON ALL TABLES ... TO chronicle_app` in init-db-roles.sql re-grants UPDATE/DELETE
 * after the one-time REVOKE, so the request-path role could tamper with the trails.
 *
 * The fix (V44) defends them with RLS policies (the proven audit_logs design), which are evaluated
 * regardless of table privileges. **This test reproduces the exact prod condition that defeated the
 * REVOKE**: it grants `chronicle_app_test` the full SELECT/INSERT/UPDATE/DELETE blanket and then
 * asserts RLS still blocks UPDATE/DELETE — i.e. the immutability is GRANT-proof. A REVOKE-only design
 * would pass-through the UPDATE/DELETE here and fail this test. It also asserts the legitimate paths
 * (study-scoped SELECT, INSERT, study isolation) keep working, and that the superuser still bypasses
 * (so legitimate retention purges remain possible) — the control documenting why the app role matters.
 *
 * Fidelity: runs the ACTUAL V44 and V90 migrations verbatim off the classpath against real table shells, with
 * the production `chronicle_has_study_access` definition. Assertions run under `SET LOCAL ROLE` to a
 * NOSUPERUSER NOBYPASSRLS role (FORCE ROW LEVEL SECURITY makes the policies apply).
 */
class AuditTrailImmutabilityRlsTest {

    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_test")

        private val STUDY_A = UUID.randomUUID()
        private val STUDY_B = UUID.randomUUID()

        // Production definition of chronicle_has_study_access (mirrors V1) — admin bypass, else
        // membership in app.authorized_studies, deny on empty/unparseable context.
        private val HAS_STUDY_ACCESS_FN = """
            CREATE OR REPLACE FUNCTION public.chronicle_has_study_access(check_study_id uuid)
            RETURNS boolean LANGUAGE plpgsql STABLE SECURITY DEFINER AS ${'$'}fn${'$'}
            DECLARE
                is_admin_user BOOLEAN;
                authorized_studies_setting TEXT;
                authorized_studies UUID[];
            BEGIN
                is_admin_user := COALESCE(NULLIF(current_setting('app.is_admin', true), '')::BOOLEAN, false);
                IF is_admin_user THEN RETURN true; END IF;
                authorized_studies_setting := current_setting('app.authorized_studies', true);
                IF authorized_studies_setting IS NULL OR authorized_studies_setting = '' THEN RETURN false; END IF;
                BEGIN
                    authorized_studies := string_to_array(authorized_studies_setting, ',')::UUID[];
                EXCEPTION WHEN OTHERS THEN RETURN false;
                END;
                RETURN check_study_id = ANY(authorized_studies);
            END;
            ${'$'}fn${'$'};
        """.trimIndent()

        private fun sqlResource(path: String): String =
            requireNotNull(AuditTrailImmutabilityRlsTest::class.java.getResourceAsStream(path)) {
                "$path not found on the classpath"
            }.bufferedReader().use { it.readText() }
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

        hds.connection.use { c ->
            c.createStatement().use { st ->
                // Minimal shells carrying the study_id the V44 policies key on.
                st.execute(
                    """
                    CREATE TABLE study_settings_audit (
                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        study_id UUID NOT NULL,
                        note TEXT
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE participant_collection_acknowledgment (
                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        study_id UUID NOT NULL,
                        participant_id TEXT NOT NULL
                    )
                    """.trimIndent(),
                )

                st.execute(HAS_STUDY_ACCESS_FN)

                st.execute("CREATE ROLE chronicle_admin NOSUPERUSER BYPASSRLS")

                // The actual fix, verbatim.
                st.execute(sqlResource("/db/migration/V44__audit_trail_rls_immutability.sql"))
                st.execute(sqlResource("/db/migration/V90__bind_mobile_withdrawal_intents.sql"))

                // Reproduce the prod condition: the role HAS the full blanket grant that defeated the
                // REVOKE. If immutability were REVOKE-based this would re-open UPDATE/DELETE.
                st.execute("CREATE ROLE $RLS_TEST_APP_ROLE NOSUPERUSER NOBYPASSRLS")
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
                st.execute("INSERT INTO study_settings_audit (study_id, note) VALUES ('$STUDY_A', 'a'), ('$STUDY_B', 'b')")
                st.execute(
                    "INSERT INTO participant_collection_acknowledgment (study_id, participant_id) " +
                        "VALUES ('$STUDY_A', 'p-a'), ('$STUDY_B', 'p-b')",
                )
                st.execute(
                    """
                    INSERT INTO mobile_withdrawal_requests
                        (request_id, api_key_id, study_id, participant_id, device_id, already_withdrawn)
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
            hds.connection.use { c ->
                c.createStatement().use { st ->
                    st.execute("DROP TABLE IF EXISTS study_settings_audit")
                    st.execute("DROP TABLE IF EXISTS participant_collection_acknowledgment")
                    st.execute("DROP TABLE IF EXISTS mobile_withdrawal_requests")
                    st.execute("DROP ROLE IF EXISTS chronicle_admin")
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

    private fun exec(c: Connection, sql: String): Int = c.createStatement().use { it.executeUpdate(sql) }

    private fun count(c: Connection, table: String): Int =
        c.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM $table").use { rs -> rs.next(); rs.getInt(1) }
        }

    @Test
    fun `study_settings_audit UPDATE is blocked despite a granted UPDATE privilege`() {
        val updated = inContext(STUDY_A.toString(), isAdmin = false) {
            exec(it, "UPDATE study_settings_audit SET note = 'tampered' WHERE study_id = '$STUDY_A'")
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
            exec(it, "INSERT INTO study_settings_audit (study_id, note) VALUES ('$STUDY_A', 'new')")
        }
        val b = inContext(STUDY_A.toString(), isAdmin = false) {
            exec(it, "INSERT INTO participant_collection_acknowledgment (study_id, participant_id) VALUES ('$STUDY_A', 'p2')")
        }
        assertEquals("append (INSERT) must remain allowed on study_settings_audit", 1, a)
        assertEquals("append (INSERT) must remain allowed on the consent trail", 1, b)
        val withdrawal = inContext(STUDY_A.toString(), isAdmin = false) {
            exec(
                it,
                """
                INSERT INTO mobile_withdrawal_requests
                    (request_id, api_key_id, study_id, participant_id, device_id, already_withdrawn)
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
                            (request_id, api_key_id, study_id, participant_id, device_id, already_withdrawn)
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
