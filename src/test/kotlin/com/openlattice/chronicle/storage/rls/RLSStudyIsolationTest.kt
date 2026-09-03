package com.openlattice.chronicle.storage.rls

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/**
 * End-to-end Row-Level Security STUDY-ISOLATION test (#3).
 *
 * Where [RLSRequestContextTest] verifies the session-variable plumbing, this verifies
 * the actual security guarantee: data from one study is invisible from another study's
 * RLS context, the database enforces it (not the app), and admin context bypasses while
 * an empty context denies everything.
 *
 * Fidelity: it pre-creates the real `study_participants` table shell, then runs the
 * ACTUAL `V1__enable_row_level_security.sql` migration verbatim off the classpath — so
 * the `chronicle_has_study_access()` function and the `study_isolation_study_participants`
 * policy under test are the production objects, not a copy (no drift). Because PostgreSQL
 * superusers bypass RLS, every assertion runs under `SET LOCAL ROLE` to a plain,
 * non-BYPASSRLS role; `FORCE ROW LEVEL SECURITY` (set by V1) makes the policy apply.
 */
class RLSStudyIsolationTest {

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

        val v1 = requireNotNull(javaClass.getResourceAsStream("/db/migration/V1__enable_row_level_security.sql")) {
            "V1 migration not found on the classpath"
        }.bufferedReader().use { it.readText() }

        hds.connection.use { c ->
            c.createStatement().use { st ->
                // Minimal shell of the real study-scoped table; the policy only needs study_id.
                st.execute(
                    """
                    CREATE TABLE study_participants (
                        study_id UUID NOT NULL,
                        participant_id TEXT NOT NULL,
                        PRIMARY KEY (study_id, participant_id)
                    )
                    """.trimIndent(),
                )
                // Run the production RLS migration verbatim: creates chronicle_has_study_access()
                // and, because study_participants now exists, enables FORCE RLS + the isolation policy.
                st.execute(v1)
                // A plain, non-superuser, non-BYPASSRLS role so the policy is actually enforced.
                st.execute("CREATE ROLE $RLS_TEST_APP_ROLE NOSUPERUSER NOBYPASSRLS")
                st.execute("GRANT SELECT, INSERT ON study_participants TO $RLS_TEST_APP_ROLE")
                // Seed one participant per study as the superuser (bypasses WITH CHECK).
                st.execute("INSERT INTO study_participants VALUES ('$STUDY_A', 'p-a'), ('$STUDY_B', 'p-b')")
            }
        }
    }

    @After
    fun tearDown() {
        if (::hds.isInitialized) {
            hds.connection.use { c ->
                c.createStatement().use { st ->
                    st.execute("DROP TABLE IF EXISTS study_participants")
                    st.execute("DROP ROLE IF EXISTS $RLS_TEST_APP_ROLE")
                }
            }
            hds.close()
        }
    }

    /** Run [block] inside a transaction under the app role + the given RLS context, then roll back. */
    private fun <T> inContext(authorizedStudies: String, isAdmin: Boolean, block: (Connection) -> T): T {
        hds.connection.use { c ->
            c.autoCommit = false
            try {
                c.applyLocalRlsTestContext(isAdmin, authorizedStudies)
                return block(c)
            } finally {
                c.rollback() // SET LOCAL + any writes are discarded
                c.autoCommit = true
            }
        }
    }

    private fun visibleStudyIds(c: Connection): Set<String> {
        c.createStatement().use { st ->
            st.executeQuery("SELECT study_id FROM study_participants").use { rs ->
                val out = mutableSetOf<String>()
                while (rs.next()) out.add(rs.getString(1))
                return out
            }
        }
    }

    @Test
    fun `study A context sees only study A rows`() {
        val visible = inContext(STUDY_A.toString(), isAdmin = false) { visibleStudyIds(it) }
        assertEquals(setOf(STUDY_A.toString()), visible)
    }

    @Test
    fun `study B context sees only study B rows`() {
        val visible = inContext(STUDY_B.toString(), isAdmin = false) { visibleStudyIds(it) }
        assertEquals(setOf(STUDY_B.toString()), visible)
    }

    @Test
    fun `multi-study context sees exactly the authorized studies`() {
        val visible = inContext("$STUDY_A,$STUDY_B", isAdmin = false) { visibleStudyIds(it) }
        assertEquals(setOf(STUDY_A.toString(), STUDY_B.toString()), visible)
    }

    @Test
    fun `empty authorized-studies context sees nothing`() {
        val visible = inContext("", isAdmin = false) { visibleStudyIds(it) }
        assertEquals(emptySet<String>(), visible)
    }

    @Test
    fun `admin context bypasses isolation and sees every study`() {
        val visible = inContext("", isAdmin = true) { visibleStudyIds(it) }
        assertEquals(setOf(STUDY_A.toString(), STUDY_B.toString()), visible)
    }

    @Test
    fun `WITH CHECK blocks inserting a row for an unauthorized study`() {
        assertThrows(SQLException::class.java) {
            inContext(STUDY_A.toString(), isAdmin = false) { c ->
                c.createStatement().use { st ->
                    // study A context may not write a study B row
                    st.executeUpdate("INSERT INTO study_participants VALUES ('$STUDY_B', 'intruder')")
                }
            }
        }
    }

    @Test
    fun `WITH CHECK allows inserting a row for an authorized study`() {
        val inserted = inContext(STUDY_A.toString(), isAdmin = false) { c ->
            c.createStatement().use { st ->
                st.executeUpdate("INSERT INTO study_participants VALUES ('$STUDY_A', 'p-a2')")
            }
        }
        assertEquals(1, inserted)
    }
}
