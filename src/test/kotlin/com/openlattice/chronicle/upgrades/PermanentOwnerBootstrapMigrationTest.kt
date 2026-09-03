package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.SystemRole
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.ids.IdConstants
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

class PermanentOwnerBootstrapMigrationTest {

    companion object {
        private lateinit var postgres: PostgreSQLContainer<*>

        @BeforeClass
        @JvmStatic
        fun setUp() {
            postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_owner_bootstrap_upgrade")
            postgres.start()
            ChronicleContractTestSchema.waitForQueryReady(postgres)
            postgres.createConnection("").use(ChronicleContractTestSchema::applyFrameworkSchema)

            val result = flywayTargeting("66").migrate()
            check(result.success) { "Flyway failed to establish the V66 upgrade boundary: ${result.warnings}" }
            check(result.targetSchemaVersion == "66") {
                "Expected the pre-V67 schema boundary, got ${result.targetSchemaVersion}"
            }
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            postgres.stop()
        }

        private fun flywayTargeting(version: String) =
            FlywayMigrationService.baseConfiguration()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .target(version)
                .load()
    }

    @Test
    fun testV67AcceptsAndProtectsSelfOwnedBootstrapRolesOnlyOnTheirOwnAcls() {
        val bootstrapRoles = listOf(
            SystemRole.AUTHENTICATED_USER.principal,
            SystemRole.ANONYMOUS_USER.principal,
        )
        val bootstrapAclKeys = bootstrapRoles.associateWith {
            listOf(IdConstants.SYSTEM_ORGANIZATION.id, UUID.randomUUID())
        }

        postgres.createConnection("").use { connection ->
            for ((principal, aclKey) in bootstrapAclKeys) {
                insertSecurableObject(connection, aclKey, "Role", principal.id)
                insertPrincipal(connection, aclKey, principal)
                insertPermanentOwner(connection, aclKey, principal)
            }
        }

        val migration = flywayTargeting("67").migrate()
        assertTrue(migration.success)
        assertEquals(1, migration.migrationsExecuted)
        assertEquals("67", migration.targetSchemaVersion)

        postgres.createConnection("").use { connection ->
            val reservedAclKey = listOf(UUID.randomUUID())
            insertSecurableObject(connection, reservedAclKey, "Study", "reserved-without-grants")
            insertEmptyPermissions(connection, reservedAclKey, SystemRole.ANONYMOUS_USER.principal)

            connection.prepareStatement(
                """
                SELECT principal_id
                FROM permissions
                WHERE principal_type = 'ROLE'
                  AND principal_id = ANY(?)
                  AND 'OWNER' = ANY(permissions)
                  AND expiration_date = 'infinity'::timestamptz
                ORDER BY principal_id
                """.trimIndent(),
            ).use { statement ->
                statement.setArray(
                    1,
                    connection.createArrayOf("text", bootstrapRoles.map { it.id }.toTypedArray()),
                )
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals(SystemRole.ANONYMOUS_USER.principal.id, resultSet.getString("principal_id"))
                    assertTrue(resultSet.next())
                    assertEquals(SystemRole.AUTHENTICATED_USER.principal.id, resultSet.getString("principal_id"))
                    assertFalse(resultSet.next())
                }
            }
        }

        val freshPrincipal = SystemRole.ANONYMOUS_USER.principal
        val originalFreshAclKey = bootstrapAclKeys.getValue(freshPrincipal)
        val replacementFreshAclKey = listOf(IdConstants.SYSTEM_ORGANIZATION.id, UUID.randomUUID())
        postgres.createConnection("").use { connection ->
            connection.prepareStatement(
                "DELETE FROM securable_objects WHERE acl_key = ?::uuid[]",
            ).use { statement ->
                statement.setString(1, postgresArrayLiteral(originalFreshAclKey))
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                "DELETE FROM permissions WHERE acl_key = ?::uuid[]",
            ).use { statement ->
                statement.setString(1, postgresArrayLiteral(originalFreshAclKey))
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(
                "DELETE FROM principals WHERE acl_key = ?::uuid[]",
            ).use { statement ->
                statement.setString(1, postgresArrayLiteral(originalFreshAclKey))
                assertEquals(1, statement.executeUpdate())
            }

            // Match HazelcastPrincipalService's fresh bootstrap order: reserve
            // the object, persist the principal, then persist its owner ACE.
            insertSecurableObject(connection, replacementFreshAclKey, "Role", freshPrincipal.id)
            insertPrincipal(connection, replacementFreshAclKey, freshPrincipal)
            insertPermanentOwner(connection, replacementFreshAclKey, freshPrincipal)
        }

        val protectedPrincipal = SystemRole.AUTHENTICATED_USER.principal
        val protectedAclKey = bootstrapAclKeys.getValue(protectedPrincipal)
        postgres.createConnection("").use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                """
                UPDATE permissions
                SET expiration_date = now() + interval '1 day'
                WHERE acl_key = ?::uuid[]
                  AND principal_type = 'ROLE'
                  AND principal_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, postgresArrayLiteral(protectedAclKey))
                statement.setString(2, protectedPrincipal.id)
                assertEquals(1, statement.executeUpdate())
            }
            val finiteOwnerFailure = assertThrows(SQLException::class.java) {
                connection.commit()
            }
            assertEquals("23514", finiteOwnerFailure.sqlState)
            connection.rollback()
        }

        val arbitraryAclKey = listOf(UUID.randomUUID())
        postgres.createConnection("").use { connection ->
            insertSecurableObject(connection, arbitraryAclKey, "Study", "not-a-bootstrap-role")

            connection.autoCommit = false
            insertPermanentOwner(connection, arbitraryAclKey, SystemRole.ANONYMOUS_USER.principal)
            val arbitraryOwnerFailure = assertThrows(SQLException::class.java) {
                connection.commit()
            }
            assertEquals("23514", arbitraryOwnerFailure.sqlState)
            connection.rollback()
        }
    }

    private fun insertSecurableObject(
        connection: Connection,
        aclKey: List<UUID>,
        objectType: String,
        name: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO securable_objects (acl_key, securable_object_type, id, name)
            VALUES (?::uuid[], ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, postgresArrayLiteral(aclKey))
            statement.setString(2, objectType)
            statement.setObject(3, aclKey.last())
            statement.setString(4, name)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun insertPrincipal(connection: Connection, aclKey: List<UUID>, principal: Principal) {
        connection.prepareStatement(
            """
            INSERT INTO principals (acl_key, principal_type, principal_id, title, description)
            VALUES (?::uuid[], ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, postgresArrayLiteral(aclKey))
            statement.setString(2, principal.type.name)
            statement.setString(3, principal.id)
            statement.setString(4, "Bootstrap ${principal.id}")
            statement.setString(5, "V67 bootstrap compatibility fixture")
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun insertPermanentOwner(connection: Connection, aclKey: List<UUID>, principal: Principal) {
        connection.prepareStatement(
            """
            INSERT INTO permissions (
                acl_key, principal_type, principal_id, permissions, expiration_date
            ) VALUES (?::uuid[], ?, ?, ARRAY['OWNER']::text[], 'infinity')
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, postgresArrayLiteral(aclKey))
            statement.setString(2, principal.type.name)
            statement.setString(3, principal.id)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun insertEmptyPermissions(connection: Connection, aclKey: List<UUID>, principal: Principal) {
        connection.prepareStatement(
            """
            INSERT INTO permissions (
                acl_key, principal_type, principal_id, permissions, expiration_date
            ) VALUES (?::uuid[], ?, ?, ARRAY[]::text[], 'infinity')
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, postgresArrayLiteral(aclKey))
            statement.setString(2, principal.type.name)
            statement.setString(3, principal.id)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun postgresArrayLiteral(aclKey: List<UUID>): String = aclKey.joinToString(",", "{", "}")
}
