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
 * End-to-end RLS test for candidate orphan WRITE isolation (HIPAA-2028 W1, V30).
 *
 * V28 made an as-yet-unlinked (orphan) candidate visible so registration's validity check can
 * confirm a standalone candidate id — but its single `chronicle_has_candidate_access()` gated
 * SELECT/UPDATE/DELETE alike, so it also let any authenticated principal UPDATE or DELETE an orphan
 * candidate. V30 splits the policies: SELECT keeps orphan visibility, UPDATE/DELETE require
 * `chronicle_has_candidate_write_access()` (admin OR study-linked-authorized, no orphan branch).
 *
 * Fidelity (no drift): pre-creates the real `candidates` + `study_participants` table shells, then
 * runs the ACTUAL V14 -> V28 -> V30 migrations verbatim off the classpath, so the functions and
 * policies under test are the production objects. Assertions run under `SET LOCAL ROLE` to a plain
 * NOSUPERUSER NOBYPASSRLS role (FORCE ROW LEVEL SECURITY makes the policies apply).
 */
class CandidateOrphanWriteIsolationRlsTest {

    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_test")

        private val STUDY_A = UUID.randomUUID()
        private val STUDY_B = UUID.randomUUID()
        private val ORPHAN_CANDIDATE = UUID.randomUUID()
        private val LINKED_CANDIDATE = UUID.randomUUID()

        private fun sqlResource(path: String): String =
            requireNotNull(CandidateOrphanWriteIsolationRlsTest::class.java.getResourceAsStream(path)) {
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
                // Minimal shells of the real study-scoped tables. The candidate access functions
                // join study_participants on candidate_id; candidates is candidate_id only.
                st.execute("CREATE TABLE candidates (candidate_id UUID PRIMARY KEY)")
                st.execute(
                    """
                    CREATE TABLE study_participants (
                        study_id UUID NOT NULL,
                        participant_id TEXT NOT NULL,
                        candidate_id UUID NOT NULL,
                        PRIMARY KEY (study_id, participant_id)
                    )
                    """.trimIndent(),
                )

                // Production migrations verbatim: V14 enables RLS + the candidate access function,
                // V28 splits into per-command policies (orphan-visible everywhere), V30 restricts
                // orphan visibility to SELECT only.
                st.execute(sqlResource("/db/migration/V14__enable_rls_on_candidates.sql"))
                st.execute(sqlResource("/db/migration/V28__loosen_control_plane_insert_rls.sql"))
                st.execute(sqlResource("/db/migration/V30__candidate_orphan_write_isolation_rls.sql"))

                st.execute("CREATE ROLE $RLS_TEST_APP_ROLE NOSUPERUSER NOBYPASSRLS")
                st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON candidates TO $RLS_TEST_APP_ROLE")

                // Seed (as superuser → bypasses RLS): one orphan candidate (no study link) and one
                // candidate linked to STUDY_A via study_participants.
                st.execute("INSERT INTO candidates VALUES ('$ORPHAN_CANDIDATE'), ('$LINKED_CANDIDATE')")
                st.execute(
                    "INSERT INTO study_participants VALUES ('$STUDY_A', 'p-a', '$LINKED_CANDIDATE')",
                )
            }
        }
    }

    @After
    fun tearDown() {
        if (::hds.isInitialized) {
            hds.connection.use { c ->
                c.createStatement().use { st ->
                    st.execute("DROP TABLE IF EXISTS candidates")
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
                c.rollback()
                c.autoCommit = true
            }
        }
    }

    private fun canSelect(c: Connection, candidateId: UUID): Boolean {
        c.prepareStatement("SELECT 1 FROM candidates WHERE candidate_id = ?").use { ps ->
            ps.setObject(1, candidateId)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    private fun deleteCandidate(c: Connection, candidateId: UUID): Int {
        c.prepareStatement("DELETE FROM candidates WHERE candidate_id = ?").use { ps ->
            ps.setObject(1, candidateId)
            return ps.executeUpdate()
        }
    }

    @Test
    fun `orphan candidate stays SELECT-visible to a non-admin (registration validity check)`() {
        // Even with no authorized studies, the orphan must be readable so registration can validate it.
        val visible = inContext(authorizedStudies = "", isAdmin = false) { canSelect(it, ORPHAN_CANDIDATE) }
        assertTrue("orphan candidate must be SELECT-visible for the registration validity check", visible)
    }

    @Test
    fun `orphan candidate is NOT deletable by a non-admin`() {
        val deleted = inContext(authorizedStudies = "$STUDY_A,$STUDY_B", isAdmin = false) {
            deleteCandidate(it, ORPHAN_CANDIDATE)
        }
        assertEquals("a non-admin must not be able to delete an orphan candidate", 0, deleted)
    }

    @Test
    fun `orphan candidate is deletable by an admin`() {
        val deleted = inContext(authorizedStudies = "", isAdmin = true) { deleteCandidate(it, ORPHAN_CANDIDATE) }
        assertEquals("an admin may delete an orphan candidate", 1, deleted)
    }

    @Test
    fun `study-linked candidate is deletable by a principal authorized for its study`() {
        val deleted = inContext(authorizedStudies = STUDY_A.toString(), isAdmin = false) {
            deleteCandidate(it, LINKED_CANDIDATE)
        }
        assertEquals("an owner of the linked study may delete the candidate", 1, deleted)
    }

    @Test
    fun `study-linked candidate is NOT deletable by a principal authorized for a different study`() {
        val deleted = inContext(authorizedStudies = STUDY_B.toString(), isAdmin = false) {
            deleteCandidate(it, LINKED_CANDIDATE)
        }
        assertEquals("a principal authorized for a different study must not delete the candidate", 0, deleted)
    }
}
