package com.openlattice.chronicle.storage.rls

import com.openlattice.chronicle.data.ParticipationStatus

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.Assert.assertThrows
import java.sql.SQLException
import java.sql.Connection
import java.util.UUID

/**
 * Candidate registration can see an unlinked ID; writes require admin or an authorized study link.
 * The complete fresh schema is applied by real Flyway, then probes use a NOSUPERUSER NOBYPASSRLS role.
 * A linked candidate cannot be deleted while enrollment still references it.
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
                st.execute("CREATE ROLE $RLS_TEST_APP_ROLE NOSUPERUSER NOBYPASSRLS")
                st.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO $RLS_TEST_APP_ROLE")
                st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON candidates TO $RLS_TEST_APP_ROLE")

                // Seed (as superuser → bypasses RLS): one orphan candidate (no study link) and one
                // candidate linked to STUDY_A via study_participants.
                st.execute("INSERT INTO studies (study_id, title) VALUES ('$STUDY_A', 'candidate study')")
                st.execute("INSERT INTO candidates (candidate_id) VALUES ('$ORPHAN_CANDIDATE'), ('$LINKED_CANDIDATE')")
                st.execute(
                    """
                    INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status) VALUES
                    ('$STUDY_A', 'p-a', '$LINKED_CANDIDATE', '${ParticipationStatus.ENROLLED.name}')
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
    fun `study-linked candidate cannot delete an enrollment through its foreign key`() {
        val failure = assertThrows(SQLException::class.java) {
            inContext(authorizedStudies = STUDY_A.toString(), isAdmin = false) {
                deleteCandidate(it, LINKED_CANDIDATE)
            }
        }
        assertEquals("23503", failure.sqlState)
    }

    @Test
    fun `study-linked candidate is NOT deletable by a principal authorized for a different study`() {
        val deleted = inContext(authorizedStudies = STUDY_B.toString(), isAdmin = false) {
            deleteCandidate(it, LINKED_CANDIDATE)
        }
        assertEquals("a principal authorized for a different study must not delete the candidate", 0, deleted)
    }
}
