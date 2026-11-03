package com.openlattice.chronicle.storage.rls

import com.openlattice.chronicle.data.ParticipationStatus

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
 * Verifies the PRODUCTION request-path privilege drop.
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
        private val CANDIDATE = UUID.randomUUID()
        private val STUDY_B = UUID.randomUUID()
        private const val AUDIT_INSERT_SQL = "INSERT INTO audit (event_id, description, acl_key, id, principal_type, " +
            "principal_id, audit_event_type, study_id, organization_id, data) VALUES (?, ?, 'fixture', " +
            "gen_random_uuid()::text, 'USER', 'fixture', 'VIEW', gen_random_uuid()::text, gen_random_uuid()::text, '{}')"
    }

    private lateinit var rawHds: HikariDataSource
    private lateinit var restrictedHds: HikariDataSource
    private lateinit var rlsHds: HikariDataSource
    private var previousAppRole: String? = null

    @Before
    fun setUp() {
        previousAppRole = RLSDataSources.appRole
        // Pool authenticates as the container superuser — mirrors the prod `chronicle` pool.
        rawHds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            maximumPoolSize = 2
            minimumIdle = 1
        })

        ChronicleContractTestSchema.resetSchemaAndMigrations(postgres)
        rawHds.connection.use { c ->
            c.createStatement().use { st ->
                // The non-superuser role the request path drops to.
                st.execute(
                "ALTER ROLE $APP_ROLE WITH LOGIN PASSWORD '$APP_PASSWORD' NOSUPERUSER NOBYPASSRLS"
                )
            }
            c.createStatement().use { statement ->
                statement.execute("INSERT INTO studies (study_id, title) VALUES ('$STUDY_A', 'study A'), ('$STUDY_B', 'study B')")
                statement.execute("INSERT INTO candidates (candidate_id) VALUES ('$CANDIDATE')")
            }
            // Seed as the superuser (bypasses WITH CHECK).
            c.prepareStatement(
                """
                INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status) VALUES
                (?, ?, '$CANDIDATE', '${ParticipationStatus.ENROLLED.name}'),
                (?, ?, '$CANDIDATE', '${ParticipationStatus.ENROLLED.name}')
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, STUDY_A)
                statement.setString(2, "p-a")
                statement.setObject(3, STUDY_B)
                statement.setString(4, "p-b")
                statement.executeUpdate()
            }
            c.prepareStatement(AUDIT_INSERT_SQL).use { statement ->
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
        RLSDataSources.appRole = APP_ROLE
    }

    @After
    fun tearDown() {
        RLSDataSources.appRole = previousAppRole
        if (::rawHds.isInitialized) {
            ChronicleContractTestSchema.clearSchema(postgres)
            if (::restrictedHds.isInitialized) {
                restrictedHds.close()
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
    fun `with no request context the connection is anonymous and cannot bypass row policies`() {
        RLSRequestContext.clear()
        rlsHds.connection.use { c ->
            val attributes = c.createStatement().use { st ->
                st.executeQuery("SELECT current_user, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user").use { rs ->
                    rs.next()
                    Triple(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3))
                }
            }
            assertEquals(APP_ROLE, attributes.first)
            assertFalse(attributes.second)
            assertFalse(attributes.third)
            assertEquals(emptySet<String>(), visibleStudyIds(c))
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
    fun `audit DELETE is rejected on the request path even for admins`() {
        assertThrows(SQLException::class.java) {
            underRequestContext(setOf(STUDY_A), isAdmin = true) { c ->
                c.createStatement().use { st -> st.executeUpdate("DELETE FROM audit") }
            }
        }
    }

    @Test
    fun `audit row policy deletes nothing even for a role holding DELETE and the admin flag`() {
        // The table owner keeps DELETE for the superuser purge procedure; the row policy is what stops it.
        rawHds.connection.use { c -> c.createStatement().use { it.execute("GRANT DELETE ON audit TO $APP_ROLE") } }
        try {
            restrictedHds.connection.use { c ->
                c.createStatement().use { st ->
                    st.execute("SET app.is_admin = 'true'")
                    assertEquals(0, st.executeUpdate("DELETE FROM audit"))
                }
            }
        } finally {
            rawHds.connection.use { c -> c.createStatement().use { it.execute("REVOKE DELETE ON audit FROM $APP_ROLE") } }
        }
    }

    @Test
    fun `audit UPDATE is rejected on the request path even for admins`() {
        assertThrows(SQLException::class.java) {
            underRequestContext(setOf(STUDY_A), isAdmin = true) { c ->
                c.createStatement().use { st -> st.executeUpdate("UPDATE audit SET description = 'tamper'") }
            }
        }
        rawHds.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT description FROM audit").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("seed", rows.getString(1))
                    assertFalse(rows.next())
                }
            }
        }
    }

    @Test
    fun `audit INSERT is allowed on the request path`() {
        val inserted = underRequestContext(setOf(STUDY_A), isAdmin = false) { c ->
            c.prepareStatement(AUDIT_INSERT_SQL).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setString(2, "request-write")
                statement.executeUpdate()
            }
        }
        assertEquals(1, inserted)
    }
}
