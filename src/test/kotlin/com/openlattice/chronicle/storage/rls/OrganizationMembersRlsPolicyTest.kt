package com.openlattice.chronicle.storage.rls

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/**
 * V57's single `FOR ALL ... USING` policy on organization_members supplies no WITH CHECK, so
 * PostgreSQL reuses the USING expression for INSERT. That expression admits any row whose
 * user_id equals app.current_user_id, letting any authenticated caller insert
 * (arbitrary organization, own user id, OWNER) and take over an organization.
 *
 * V102 splits reads from writes: a member still reads their own row, but writes require admin
 * context or an organization the caller is authorized on.
 */
class OrganizationMembersRlsPolicyTest {

    companion object {
        private const val APP_ROLE = "chronicle_app_org_rls"
        private const val APP_PASSWORD = "chronicle-app-test-password"
        private lateinit var postgres: PostgreSQLContainer<*>

        @BeforeClass
        @JvmStatic
        fun setUp() {
            postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_org_members_rls")
            postgres.start()
            ChronicleContractTestSchema.waitForQueryReady(postgres)
            postgres.createConnection("").use { connection ->
                applyMigration(connection, "V57__reissue_org_members_quotas.sql")
                applyMigration(connection, "V102__org_members_write_policy.sql")
                connection.createStatement().use { statement ->
                    statement.execute(
                        "CREATE ROLE $APP_ROLE WITH LOGIN PASSWORD '$APP_PASSWORD' NOSUPERUSER NOBYPASSRLS"
                    )
                    statement.execute(
                        "GRANT SELECT, INSERT, UPDATE, DELETE ON organization_members TO $APP_ROLE"
                    )
                }
            }
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            postgres.stop()
        }

        private fun applyMigration(connection: Connection, name: String) {
            val sql = requireNotNull(
                OrganizationMembersRlsPolicyTest::class.java.getResource("/db/migration/$name")
            ) { "Missing migration $name" }.readText()
            connection.createStatement().use { it.execute(sql) }
        }
    }

    private fun appConnection(userId: String, authorizedOrgs: String, admin: Boolean = false): Connection {
        val connection = java.sql.DriverManager.getConnection(postgres.jdbcUrl, APP_ROLE, APP_PASSWORD)
        connection.prepareStatement(
            "SELECT set_config('app.current_user_id', ?, false), " +
                "set_config('app.is_admin', ?, false), set_config('app.authorized_orgs', ?, false)"
        ).use { statement ->
            statement.setString(1, userId)
            statement.setString(2, admin.toString())
            statement.setString(3, authorizedOrgs)
            statement.executeQuery().use { it.next() }
        }
        return connection
    }

    private fun insertMembership(connection: Connection, organizationId: UUID, userId: String, role: String): Int =
        connection.prepareStatement(
            "INSERT INTO organization_members (organization_id, user_id, role) VALUES (?, ?, ?)"
        ).use { statement ->
            statement.setObject(1, organizationId)
            statement.setString(2, userId)
            statement.setString(3, role)
            statement.executeUpdate()
        }

    @Test
    fun anOutsiderCannotSelfGrantOwnershipOfAnArbitraryOrganization() {
        val victimOrganization = UUID.randomUUID()
        appConnection(userId = "outsider", authorizedOrgs = "").use { connection ->
            val failure = try {
                insertMembership(connection, victimOrganization, "outsider", "OWNER")
                null
            } catch (e: SQLException) {
                e
            }
            assertTrue(
                "Self-granting OWNER on an unauthorized organization must be rejected by RLS",
                failure != null,
            )
        }
    }

    @Test
    fun anAuthorizedOrganizationStillAcceptsMembershipWrites() {
        val organizationId = UUID.randomUUID()
        appConnection(userId = "admin-user", authorizedOrgs = organizationId.toString()).use { connection ->
            assertEquals(1, insertMembership(connection, organizationId, "new-member", "VIEWER"))
        }
    }

    @Test
    fun aMemberCanStillReadTheirOwnMembership() {
        val organizationId = UUID.randomUUID()
        appConnection(userId = "seed-admin", authorizedOrgs = organizationId.toString()).use { connection ->
            insertMembership(connection, organizationId, "self-reader", "VIEWER")
        }
        appConnection(userId = "self-reader", authorizedOrgs = "").use { connection ->
            connection.prepareStatement(
                "SELECT role FROM organization_members WHERE organization_id = ? AND user_id = 'self-reader'"
            ).use { statement ->
                statement.setObject(1, organizationId)
                statement.executeQuery().use { rs ->
                    assertTrue("A member must still be able to read their own row", rs.next())
                    assertEquals("VIEWER", rs.getString("role"))
                }
            }
        }
    }
}
