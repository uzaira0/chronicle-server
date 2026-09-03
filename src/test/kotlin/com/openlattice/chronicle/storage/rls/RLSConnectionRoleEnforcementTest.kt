package com.openlattice.chronicle.storage.rls

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/**
 * Verifies the PRODUCTION request-path privilege drop — the W1 fix.
 *
 * [RLSStudyIsolationTest] proves the RLS *policy* is correct by manually issuing
 * `SET LOCAL ROLE` to a non-superuser role. That cannot catch the actual production
 * gap: the connection pool authenticates as the owner/superuser `chronicle`, and a
 * superuser bypasses RLS before any policy is consulted — so the per-request session
 * variables are inert and isolation silently does not happen.
 *
 * This test drives the REAL [RLSDataSources] wrapper + [RLSRequestContext] (the exact
 * objects the running server uses). The Hikari pool here authenticates as the
 * Testcontainers superuser, exactly mirroring prod. The only thing that makes RLS and
 * the audit-immutability REVOKEs engage is the `SET ROLE "chronicle_app"` the wrapper
 * performs in [RLSConnectionSql.applyContext]. If that drop is ever removed, the
 * `current_user` / `rolsuper` assertion below fails — this is the regression guard for
 * the production gap.
 */
class RLSConnectionRoleEnforcementTest {

    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_test")

        private const val APP_ROLE = "chronicle_app"
        private const val APP_PASSWORD = "chronicle-app-test-password"
        private val STUDY_A = UUID.randomUUID()
        private val STUDY_B = UUID.randomUUID()
    }

    private lateinit var rawHds: HikariDataSource
    private lateinit var restrictedHds: HikariDataSource
    private lateinit var rlsHds: HikariDataSource
    private var previousAppRole: String? = null

    @Before
    fun setUp() {
        // Pool authenticates as the container superuser — mirrors the prod `chronicle` pool.
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
            }
            // Production RLS objects, verbatim: chronicle_has_study_access() + FORCE RLS + policy.
            installProductionRlsMigration(c)
            c.createStatement().use { st ->
                // Minimal append-only audit table whose immutability is enforced by role-level REVOKE.
                st.execute("CREATE TABLE audit_logs (id UUID PRIMARY KEY, action TEXT NOT NULL)")
                // The non-superuser role the request path drops to.
                st.execute(
                    "CREATE ROLE $APP_ROLE WITH LOGIN PASSWORD '$APP_PASSWORD' NOSUPERUSER NOBYPASSRLS"
                )
                st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON study_participants TO $APP_ROLE")
                st.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON audit_logs TO $APP_ROLE")
                // Audit immutability (mirrors V15/V25/V26): append-only for the application role.
                st.execute("REVOKE UPDATE, DELETE ON audit_logs FROM $APP_ROLE")
            }
            // Seed as the superuser (bypasses WITH CHECK).
            c.prepareStatement(
                "INSERT INTO study_participants VALUES (?, ?), (?, ?)",
            ).use { statement ->
                statement.setObject(1, STUDY_A)
                statement.setString(2, "p-a")
                statement.setObject(3, STUDY_B)
                statement.setString(4, "p-b")
                statement.executeUpdate()
            }
            c.prepareStatement("INSERT INTO audit_logs VALUES (?, ?)").use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setString(2, "seed")
                statement.executeUpdate()
            }
        }

        restrictedHds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = APP_ROLE
            password = APP_PASSWORD
            maximumPoolSize = 1
            minimumIdle = 1
        })
        rlsHds = RLSDataSources.wrapIfRequestScoped(rawHds)
        previousAppRole = RLSDataSources.appRole
        RLSDataSources.appRole = APP_ROLE
    }

    private fun installProductionRlsMigration(connection: Connection) {
        val migration = requireNotNull(
            javaClass.getResourceAsStream("/db/migration/V1__enable_row_level_security.sql"),
        ) {
            "V1 migration not found on the classpath"
        }.bufferedReader().use { it.readText() }
        connection.createStatement().use { statement ->
            statement.execute(migration)
        }
    }

    @After
    fun tearDown() {
        RLSDataSources.appRole = previousAppRole
        if (::rawHds.isInitialized) {
            if (::restrictedHds.isInitialized) {
                restrictedHds.close()
            }
            rawHds.connection.use { c ->
                c.createStatement().use { st ->
                    st.execute("DROP TABLE IF EXISTS study_participants")
                    st.execute("DROP TABLE IF EXISTS audit_logs")
                    st.execute("DROP ROLE IF EXISTS $APP_ROLE")
                }
            }
            rawHds.close()
        }
    }

    /** Borrow a wrapped connection with the given RLS request context, then clean up. */
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
            st.executeQuery("SELECT study_id FROM study_participants").use { rs ->
                val out = mutableSetOf<String>()
                while (rs.next()) out.add(rs.getString(1))
                return out
            }
        }
    }

    @Test
    fun `wrapper drops the request connection to the non-superuser app role`() {
        val (role, isSuper) = underRequestContext(setOf(STUDY_A), isAdmin = false) { c ->
            c.createStatement().use { st ->
                st.executeQuery(
                    "SELECT current_user, (SELECT rolsuper FROM pg_roles WHERE rolname = current_user)",
                ).use { rs ->
                    rs.next()
                    rs.getString(1) to rs.getBoolean(2)
                }
            }
        }
        assertEquals(APP_ROLE, role)
        assertFalse("request-path connection must not run as a superuser", isSuper)
    }

    @Test
    fun `study A request context sees only study A rows`() {
        val visible = underRequestContext(setOf(STUDY_A), isAdmin = false) { visibleStudyIds(it) }
        assertEquals(setOf(STUDY_A.toString()), visible)
    }

    @Test
    fun `study B request context sees only study B rows`() {
        val visible = underRequestContext(setOf(STUDY_B), isAdmin = false) { visibleStudyIds(it) }
        assertEquals(setOf(STUDY_B.toString()), visible)
    }

    @Test
    fun `empty context sees nothing`() {
        val visible = underRequestContext(emptySet(), isAdmin = false) { visibleStudyIds(it) }
        assertEquals(emptySet<String>(), visible)
    }

    @Test
    fun `admin context bypasses isolation and sees every study`() {
        val visible = underRequestContext(emptySet(), isAdmin = true) { visibleStudyIds(it) }
        assertEquals(setOf(STUDY_A.toString(), STUDY_B.toString()), visible)
    }

    @Test
    fun `system wrapper lets restricted background pool drain all studies and clears on return`() {
        restrictedHds.connection.use { connection ->
            assertEquals(emptySet<String>(), visibleStudyIds(connection))
        }

        val systemHds = RLSDataSources.wrapWithSystemContext(restrictedHds)
        assertEquals(
            setOf(STUDY_A.toString(), STUDY_B.toString()),
            systemHds.connection.use { connection ->
                assertEquals(APP_ROLE, connection.metaData.userName)
                assertEquals("true", currentSetting(connection, "app.is_admin"))
                visibleStudyIds(connection)
            },
        )

        restrictedHds.connection.use { connection ->
            assertEquals("false", currentSetting(connection, "app.is_admin"))
            assertEquals(emptySet<String>(), visibleStudyIds(connection))
        }
    }

    @Test
    fun `with no request context the connection keeps the privileged pool role`() {
        // No RLSRequestContext -> wrapper returns the raw connection: this is the bootstrap /
        // upgrade-runner path, which must keep full privileges and (as a superuser) bypass RLS.
        rlsHds.connection.use { c ->
            val isSuper = c.createStatement().use { st ->
                st.executeQuery("SELECT rolsuper FROM pg_roles WHERE rolname = current_user").use { rs ->
                    rs.next()
                    rs.getBoolean(1)
                }
            }
            assertTrue("bootstrap path must keep the privileged pool role", isSuper)
            assertEquals(setOf(STUDY_A.toString(), STUDY_B.toString()), visibleStudyIds(c))
        }
    }

    private fun currentSetting(connection: Connection, setting: String): String? {
        connection.prepareStatement("SELECT current_setting(?, true)").use { statement ->
            statement.setString(1, setting)
            statement.executeQuery().use { rs ->
                rs.next()
                return rs.getString(1)
            }
        }
    }

    @Test
    fun `audit_logs DELETE is rejected on the request path even for admins`() {
        assertThrows(SQLException::class.java) {
            underRequestContext(setOf(STUDY_A), isAdmin = true) { c ->
                c.createStatement().use { st -> st.executeUpdate("DELETE FROM audit_logs") }
            }
        }
    }

    @Test
    fun `audit_logs UPDATE is rejected on the request path even for admins`() {
        assertThrows(SQLException::class.java) {
            underRequestContext(setOf(STUDY_A), isAdmin = true) { c ->
                c.createStatement().use { st -> st.executeUpdate("UPDATE audit_logs SET action = 'tamper'") }
            }
        }
    }

    @Test
    fun `audit_logs INSERT is allowed on the request path`() {
        val inserted = underRequestContext(setOf(STUDY_A), isAdmin = false) { c ->
            c.prepareStatement("INSERT INTO audit_logs VALUES (?, ?)").use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setString(2, "request-write")
                statement.executeUpdate()
            }
        }
        assertEquals(1, inserted)
    }
}
