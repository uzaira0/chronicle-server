package com.openlattice.chronicle.storage.rls

import com.openlattice.chronicle.data.ParticipationStatus

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** Study isolation under non-bypass connections against the complete fresh schema. */
class RLSStudyIsolationTest {

    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_test")

        private val STUDY_A = UUID.randomUUID()
        private val CANDIDATE = UUID.randomUUID()
        private const val RLS_DENIED = "42501"

        /** The audit trail records events, refusals included, against any study; rows are append-only. */
        private const val AUDIT_APPEND_POLICY = "audit.audit_insert"

        /** Study-scoped tables whose INSERT policy must check the study, not `true`. */
        private val STUDY_SCOPED_INSERT_TABLES = listOf(
            "participant_collection_acknowledgment", "filtered_apps", "organization_studies",
            "study_limits", "study_settings_audit",
        )
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
                // A plain, non-superuser, non-BYPASSRLS role so the policy is actually enforced.
                st.execute("CREATE ROLE $RLS_TEST_APP_ROLE NOSUPERUSER NOBYPASSRLS")
                st.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO $RLS_TEST_APP_ROLE")
                st.execute("GRANT SELECT, INSERT ON study_participants TO $RLS_TEST_APP_ROLE")
                st.execute("GRANT SELECT ON mobile_withdrawal_requests TO $RLS_TEST_APP_ROLE")
                st.execute("GRANT INSERT ON ${STUDY_SCOPED_INSERT_TABLES.joinToString()} TO $RLS_TEST_APP_ROLE")
            }
            c.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, 'study A'), (?, 'study B')").use { statement ->
                statement.setObject(1, STUDY_A)
                statement.setObject(2, STUDY_B)
                statement.executeUpdate()
            }
            c.prepareStatement("INSERT INTO candidates (candidate_id) VALUES (?)").use { statement ->
                statement.setObject(1, CANDIDATE)
                statement.executeUpdate()
            }
            // Seed one participant per study as the superuser (bypasses WITH CHECK).
            c.prepareStatement(
                "INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status) " +
                    "VALUES (?, ?, ?, ?)",
            ).use { insert ->
                listOf(STUDY_A to "p-a", STUDY_B to "p-b").forEach { (study, participant) ->
                    insert.setObject(1, study)
                    insert.setString(2, participant)
                    insert.setObject(3, CANDIDATE)
                    insert.setString(4, ParticipationStatus.ENROLLED.name)
                    insert.executeUpdate()
                }
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
                    st.executeUpdate(
                        """
                        INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status) VALUES
                        ('$STUDY_B', 'intruder', '$CANDIDATE', '${ParticipationStatus.ENROLLED.name}')
                        """.trimIndent(),
                    )
                }
            }
        }
    }

    /**
     * PostgreSQL applies the RLS WITH CHECK before NOT NULL/CHECK constraints, so a study_id-only
     * row isolates the policy: foreign studies get 42501, the authorized study gets past it.
     */
    @Test
    fun `study-scoped INSERT policies refuse rows for an unauthorized study`() {
        for (table in STUDY_SCOPED_INSERT_TABLES) {
            assertEquals(table, RLS_DENIED, insertStudyIdOnly(table, STUDY_B))
            assertNotEquals(table, RLS_DENIED, insertStudyIdOnly(table, STUDY_A))
        }
    }

    /**
     * Every table, not a list: with every privilege granted, each table carrying a study_id forces RLS and
     * no permissive INSERT or UPDATE policy on one checks `true`, so a new table or policy cannot slip past.
     */
    @Test
    fun `every study-scoped table refuses foreign-study writes despite blanket grants`() {
        val forced = hds.connection.use { c ->
            c.createStatement().use { st ->
                st.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO $RLS_TEST_APP_ROLE")
                st.executeQuery("""
                    SELECT relation.relname, relation.relrowsecurity AND relation.relforcerowsecurity
                    FROM pg_class relation
                    JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
                    JOIN pg_attribute attribute ON attribute.attrelid = relation.oid
                    WHERE namespace.nspname = 'public' AND relation.relkind IN ('r', 'p')
                      AND attribute.attname = 'study_id' AND NOT attribute.attisdropped
                    ORDER BY 1
                """.trimIndent()).use { rows -> buildMap { while (rows.next()) put(rows.getString(1), rows.getBoolean(2)) } }
            }
        }
        assertTrue("the catalog query must find the study tables", forced.keys.containsAll(STUDY_SCOPED_INSERT_TABLES))
        assertEquals(
            "study tables without forced RLS",
            emptySet<String>(),
            forced.filterValues { !it }.keys,
        )
        val unscopedWrites = hds.connection.use { c ->
            c.prepareStatement("""
                SELECT tablename || '.' || policyname FROM pg_policies
                WHERE schemaname = 'public' AND tablename = ANY (?) AND permissive = 'PERMISSIVE'
                  AND cmd IN ('INSERT', 'UPDATE', 'ALL') AND btrim(COALESCE(with_check, qual), '() ') = 'true'
                ORDER BY 1
            """.trimIndent()).use { statement ->
                statement.setArray(1, c.createArrayOf("text", forced.keys.toTypedArray()))
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }
        assertEquals("permissive write policies that check true", listOf(AUDIT_APPEND_POLICY, "audit_buffer.audit_buffer_insert"), unscopedWrites)
        for (table in STUDY_SCOPED_INSERT_TABLES) {
            assertEquals(table, RLS_DENIED, insertStudyIdOnly(table, STUDY_B))
        }
    }

    private fun insertStudyIdOnly(table: String, study: UUID): String? = try {
        inContext(STUDY_A.toString(), isAdmin = false) { c ->
            c.createStatement().use { it.executeUpdate("INSERT INTO $table (study_id) VALUES ('$study')") }
        }
        null
    } catch (failure: SQLException) {
        failure.sqlState
    }

    @Test
    fun `WITH CHECK allows inserting a row for an authorized study`() {
        val inserted = inContext(STUDY_A.toString(), isAdmin = false) { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    """
                    INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status) VALUES
                    ('$STUDY_A', 'p-a2', '$CANDIDATE', '${ParticipationStatus.ENROLLED.name}')
                    """.trimIndent(),
                )
            }
        }
        assertEquals(1, inserted)
    }
}
