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
 * Validates that `V29__encrypted_payloads.sql` actually study-isolates the PHI ciphertext
 * table (HIPAA-2028 W2). Companion to [RLSConnectionRoleEnforcementTest]: that one proves
 * the request-path role drop; this one proves V29's RLS policy + dedup constraint on the
 * real table, run verbatim against Postgres under the production [RLSDataSources] wrapper.
 */
class EncryptedPayloadsRlsTest {

    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_test")

        private const val APP_ROLE = "chronicle_app"
        private val STUDY_A = UUID.randomUUID()
        private val STUDY_B = UUID.randomUUID()

        private val INSERT_SQL = """
            INSERT INTO encrypted_payloads (
                payload_id, study_id, participant_id, device_id, payload_type,
                envelope_version, alg, key_id, encrypted_key, iv, ciphertext,
                sample_count, content_hash
            )
            VALUES (?, ?, ?, ?, 'sensor', 1, 'RSA-OAEP-256+A256GCM', 'k', ?, ?, ?, 1, ?)
        """.trimIndent()
    }

    private lateinit var rawHds: HikariDataSource
    private lateinit var rlsHds: HikariDataSource
    private var previousAppRole: String? = null

    private fun resource(path: String): String =
        requireNotNull(javaClass.getResourceAsStream(path)) { "$path not found on the classpath" }
            .bufferedReader().use { it.readText() }

    @Before
    fun setUp() {
        rawHds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 2
            minimumIdle = 1
        })

        rawHds.connection.use { c ->
            c.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE study_participants (
                        study_id UUID NOT NULL,
                        participant_id TEXT NOT NULL,
                        PRIMARY KEY (study_id, participant_id)
                    )
                    """.trimIndent(),
                )
                // V1 creates chronicle_has_study_access() (and applies RLS to existing tables).
                st.execute(resource("/db/migration/V1__enable_row_level_security.sql"))
                // The non-superuser role the request path drops to (must exist before V29's GRANT).
                st.execute("CREATE ROLE $APP_ROLE WITH LOGIN NOSUPERUSER NOBYPASSRLS")
                // V29 creates encrypted_payloads, enables/forces RLS, adds the policy, and grants.
                st.execute(resource("/db/migration/V29__encrypted_payloads.sql"))
                // Seed one row per study as the superuser (bypasses WITH CHECK).
                seed(c, STUDY_A, "p-a", byteArrayOf(1))
                seed(c, STUDY_B, "p-b", byteArrayOf(2))
            }
        }

        rlsHds = RLSDataSources.wrapIfRequestScoped(rawHds)
        previousAppRole = RLSDataSources.appRole
        RLSDataSources.appRole = APP_ROLE
    }

    @After
    fun tearDown() {
        RLSDataSources.appRole = previousAppRole
        if (::rawHds.isInitialized) {
            rawHds.connection.use { c ->
                c.createStatement().use { st ->
                    st.execute("DROP TABLE IF EXISTS encrypted_payloads")
                    st.execute("DROP TABLE IF EXISTS study_participants")
                    st.execute("DROP ROLE IF EXISTS $APP_ROLE")
                }
            }
            rawHds.close()
        }
    }

    private fun seed(c: Connection, studyId: UUID, participantId: String, contentHash: ByteArray) {
        c.prepareStatement(INSERT_SQL).use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setObject(2, studyId)
            ps.setString(3, participantId)
            ps.setObject(4, UUID.randomUUID())
            ps.setBytes(5, byteArrayOf(9))
            ps.setBytes(6, byteArrayOf(8))
            ps.setBytes(7, byteArrayOf(7))
            ps.setBytes(8, contentHash)
            ps.executeUpdate()
        }
    }

    private fun <T> underRequestContext(
        authorizedStudies: Set<UUID>,
        isAdmin: Boolean,
        block: (Connection) -> T,
    ): T {
        RLSRequestContext.set(RLSConnectionContext("tester", authorizedStudies, isAdmin))
        try {
            rlsHds.connection.use { c -> return block(c) }
        } finally {
            RLSRequestContext.clear()
        }
    }

    private fun visibleStudyIds(c: Connection): Set<String> {
        c.createStatement().use { st ->
            st.executeQuery("SELECT study_id FROM encrypted_payloads").use { rs ->
                val out = mutableSetOf<String>()
                while (rs.next()) out.add(rs.getString(1))
                return out
            }
        }
    }

    @Test
    fun `study A context sees only study A ciphertext rows`() {
        assertEquals(
            setOf(STUDY_A.toString()),
            underRequestContext(setOf(STUDY_A), isAdmin = false) { visibleStudyIds(it) },
        )
    }

    @Test
    fun `study B context cannot see study A ciphertext rows`() {
        assertEquals(
            setOf(STUDY_B.toString()),
            underRequestContext(setOf(STUDY_B), isAdmin = false) { visibleStudyIds(it) },
        )
    }

    @Test
    fun `empty context sees no ciphertext rows`() {
        assertEquals(emptySet<String>(), underRequestContext(emptySet(), isAdmin = false) { visibleStudyIds(it) })
    }

    @Test
    fun `admin context sees every study's ciphertext rows`() {
        assertEquals(
            setOf(STUDY_A.toString(), STUDY_B.toString()),
            underRequestContext(emptySet(), isAdmin = true) { visibleStudyIds(it) },
        )
    }

    @Test
    fun `participant can insert ciphertext for their own study`() {
        val inserted = underRequestContext(setOf(STUDY_A), isAdmin = false) { c ->
            c.prepareStatement(INSERT_SQL).use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setObject(2, STUDY_A)
                ps.setString(3, "p-a")
                ps.setObject(4, UUID.randomUUID())
                ps.setBytes(5, byteArrayOf(9))
                ps.setBytes(6, byteArrayOf(8))
                ps.setBytes(7, byteArrayOf(7))
                ps.setBytes(8, byteArrayOf(42))
                ps.executeUpdate()
            }
        }
        assertEquals(1, inserted)
    }

    @Test
    fun `participant cannot insert ciphertext for another study`() {
        assertThrows(SQLException::class.java) {
            underRequestContext(setOf(STUDY_A), isAdmin = false) { c ->
                c.prepareStatement(INSERT_SQL).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setObject(2, STUDY_B)
                    ps.setString(3, "p-b")
                    ps.setObject(4, UUID.randomUUID())
                    ps.setBytes(5, byteArrayOf(9))
                    ps.setBytes(6, byteArrayOf(8))
                    ps.setBytes(7, byteArrayOf(7))
                    ps.setBytes(8, byteArrayOf(43))
                    ps.executeUpdate()
                }
            }
        }
    }

    @Test
    fun `duplicate content hash for a study-participant is rejected by the dedup constraint`() {
        rawHds.connection.use { c ->
            seed(c, STUDY_A, "p-dup", byteArrayOf(100))
            assertThrows(SQLException::class.java) { seed(c, STUDY_A, "p-dup", byteArrayOf(100)) }
        }
    }
}
