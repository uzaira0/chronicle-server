package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

class StudyEncryptionExportGateMigrationTest {
    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_encryption_export_gate")
    }

    @Test
    fun `migration refuses an enabled encryption setting that exports cannot decrypt`() {
        withGateSchema { connection ->
            insertStudy(connection, "{\"Encryption\":{\"enabled\":true}}")

            val failure = assertThrows(SQLException::class.java) { applyMigration(connection) }
            assertTrue(failure.message.orEmpty().contains("disable it before upgrading"))
            assertFalse(constraintExists(connection, "studies_encryption_export_supported"))
        }
    }

    @Test
    fun `migration refuses historical ciphertext even when encryption is disabled`() {
        withGateSchema { connection ->
            insertStudy(connection, "{\"Encryption\":{\"enabled\":false}}")
            connection.createStatement().use {
                it.execute("INSERT INTO encrypted_payloads VALUES ('${UUID.randomUUID()}')")
            }

            val failure = assertThrows(SQLException::class.java) { applyMigration(connection) }
            assertTrue(failure.message.orEmpty().contains("encrypted_payloads contains ciphertext"))
            assertFalse(constraintExists(connection, "encrypted_payloads_export_supported"))
        }
    }

    @Test
    fun `clean migration prevents future encryption settings and ciphertext at the database boundary`() {
        withGateSchema { connection ->
            insertStudy(connection, "{\"Encryption\":{\"enabled\":false}}")
            applyMigration(connection)

            assertTrue(constraintExists(connection, "studies_encryption_export_supported"))
            assertTrue(constraintExists(connection, "encrypted_payloads_export_supported"))
            assertThrows(SQLException::class.java) {
                insertStudy(connection, "{\"Encryption\":{\"enabled\":true}}")
            }
            assertThrows(SQLException::class.java) {
                connection.createStatement().use {
                    it.execute("INSERT INTO encrypted_payloads VALUES ('${UUID.randomUUID()}')")
                }
            }
        }
    }

    private fun withGateSchema(test: (Connection) -> Unit) {
        postgres.createConnection("").use { connection ->
            val schema = "encryption_gate_${UUID.randomUUID().toString().replace("-", "")}"
            connection.createStatement().use { statement ->
                statement.execute("CREATE SCHEMA $schema")
                statement.execute("SET search_path TO $schema, public")
                statement.execute("CREATE TABLE studies (study_id UUID PRIMARY KEY, settings JSONB NOT NULL)")
                statement.execute("CREATE TABLE encrypted_payloads (id UUID PRIMARY KEY)")
            }
            try {
                test(connection)
            } finally {
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO public")
                    statement.execute("DROP SCHEMA $schema CASCADE")
                }
            }
        }
    }

    private fun insertStudy(connection: Connection, settings: String) {
        connection.prepareStatement("INSERT INTO studies VALUES (?, ?::jsonb)").use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setString(2, settings)
            statement.executeUpdate()
        }
    }

    private fun applyMigration(connection: Connection) {
        connection.createStatement().use { it.execute(migration) }
    }

    private fun constraintExists(connection: Connection, name: String): Boolean =
        connection.prepareStatement(
            "SELECT EXISTS (" +
                "SELECT 1 FROM pg_constraint " +
                "WHERE conname = ? AND connamespace = current_schema()::regnamespace" +
                ")",
        ).use { statement ->
            statement.setString(1, name)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next())
                resultSet.getBoolean(1)
            }
        }

    private val migration: String
        get() = requireNotNull(
            javaClass.getResourceAsStream("/db/migration/V95__fail_closed_unexportable_study_encryption.sql"),
        ).bufferedReader().use { it.readText() }
}
