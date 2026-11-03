package com.openlattice.chronicle.storage.rls

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer.withAdminContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.ClassRule
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/** Fresh-schema revision authority under a non-bypass function owner and hostile grants. */
class DataCollectionRevisionLedgerRlsTest {
    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_revision_rls_test")

        private const val MIGRATION_ROLE = "chronicle_revision_migrator_test"

    }

    @Test
    fun `non bypass migration owner can record through trigger while direct ledger writes stay denied`() {
        freshDatabase("revision_owner").use { connection ->
            withAdminContext(connection) {
                configureRestrictedLedgerOwner(connection)
                val studyId = UUID.randomUUID()
                insertStudy(connection, studyId, "{}")

                replayBlanketRuntimeGrantsAndFinalRevokes(connection)

                assertFalse(hasFunctionPrivilege(connection, "chronicle_app"))
                assertFalse(hasFunctionPrivilege(connection, "chronicle_admin"))
                assertFalse(hasSchemaCreatePrivilege(connection, "chronicle_app"))
                assertFalse(hasSchemaCreatePrivilege(connection, "chronicle_admin"))

                connection.autoCommit = false
                connection.createStatement().use { it.execute("SET LOCAL ROLE chronicle_app") }
                connection.prepareStatement(
                    "UPDATE studies SET settings = ?::jsonb WHERE study_id = ?",
                ).use {
                    it.setString(1, "{\"DataCollection\":${dataCollectionSettings(version = 7, enabled = true)}}")
                    it.setObject(2, studyId)
                    assertEquals(1, it.executeUpdate())
                }
                connection.commit()
                connection.autoCommit = true

                assertEquals(1, ledgerCount(connection, studyId, 7))
                assertEquals(1, ledgerCountAsRole(connection, "chronicle_app", studyId, 7))
                assertDirectInsertDenied(connection, "chronicle_app", studyId, version = 8)
                assertDirectInsertDenied(connection, "chronicle_admin", studyId, version = 9)
                assertDirectInsertDenied(connection, MIGRATION_ROLE, studyId, version = 10)

                listOf("UPDATE", "DELETE", "TRUNCATE").forEach { operation ->
                    assertMutationDeniedForRuntimeAdmin(connection, operation, studyId)
                }
                assertCompatibleAttackerTriggerRejected(connection, studyId)
            }
        }
    }

    @Test
    fun `unversioned and malformed collection policy is rejected while versioned policy is recorded`() {
        freshDatabase("revision_new_study").use { connection ->
            withAdminContext(connection) {
                configureRestrictedLedgerOwner(connection)
                for (candidate in listOf("""{"appUsageFrequency":"DAILY"}""", """{"settingsVersion":null}""")) {
                    val studyId = UUID.randomUUID()
                    assertThrows(SQLException::class.java) {
                        insertStudy(connection, studyId, "{\"DataCollection\":$candidate}")
                    }
                    assertEquals(0, studyCount(connection, studyId))
                    assertEquals(0, ledgerCountForStudy(connection, studyId))
                }
                val versionedStudyId = UUID.randomUUID()
                insertStudy(connection, versionedStudyId,
                    "{\"DataCollection\":${dataCollectionSettings(version = 1, enabled = false)}}")
                assertEquals(1, ledgerCount(connection, versionedStudyId, version = 1))
            }
        }
    }

    private fun insertStudy(connection: Connection, studyId: UUID, settingsJson: String) {
        connection.prepareStatement("INSERT INTO studies (study_id, title, settings) VALUES (?, 'revision fixture', ?::jsonb)").use {
            it.setObject(1, studyId)
            it.setString(2, settingsJson)
            assertEquals(1, it.executeUpdate())
        }
    }

    private fun freshDatabase(prefix: String): Connection {
        val jdbcUrl = ChronicleContractTestSchema.createDatabase(postgres, prefix)
        val connection = DriverManager.getConnection(jdbcUrl, postgres.username, postgres.password)
        ChronicleContractTestSchema.applyFrameworkSchema(connection)
        check(ChronicleContractTestSchema.flywayFor(jdbcUrl, postgres.username, postgres.password).migrate().success)
        return connection
    }

    private fun configureRestrictedLedgerOwner(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                DO ${'$'}${'$'} BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = '$MIGRATION_ROLE') THEN
                        CREATE ROLE $MIGRATION_ROLE NOLOGIN NOSUPERUSER NOBYPASSRLS;
                    END IF;
                END ${'$'}${'$'};
                """.trimIndent(),
            )
            statement.execute("GRANT USAGE, CREATE ON SCHEMA public TO $MIGRATION_ROLE")
            statement.execute("ALTER TABLE studies OWNER TO $MIGRATION_ROLE")
            statement.execute("ALTER TABLE study_settings_audit OWNER TO $MIGRATION_ROLE")
            statement.execute("ALTER TABLE data_collection_settings_revisions OWNER TO $MIGRATION_ROLE")
            statement.execute("ALTER FUNCTION record_data_collection_settings_revision() OWNER TO $MIGRATION_ROLE")
        }
    }

    private fun replayBlanketRuntimeGrantsAndFinalRevokes(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON ALL TABLES IN SCHEMA public TO chronicle_app")
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON ALL TABLES IN SCHEMA public TO chronicle_admin")
            statement.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO chronicle_app, chronicle_admin")
            statement.execute("GRANT CREATE ON SCHEMA public TO chronicle_app, chronicle_admin")
            statement.execute(
                "REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON data_collection_settings_revisions " +
                    "FROM chronicle_app, chronicle_admin",
            )
            statement.execute(
                "REVOKE EXECUTE ON FUNCTION record_data_collection_settings_revision() " +
                    "FROM chronicle_app, chronicle_admin",
            )
            statement.execute("REVOKE CREATE ON SCHEMA public FROM chronicle_app, chronicle_admin")
        }
    }

    private fun hasFunctionPrivilege(connection: Connection, role: String): Boolean =
        connection.prepareStatement(
            "SELECT has_function_privilege(?, 'record_data_collection_settings_revision()', 'EXECUTE')",
        ).use { statement ->
            statement.setString(1, role)
            statement.executeQuery().use { result -> result.next(); result.getBoolean(1) }
        }

    private fun hasSchemaCreatePrivilege(connection: Connection, role: String): Boolean =
        connection.prepareStatement("SELECT has_schema_privilege(?, 'public', 'CREATE')").use { statement ->
            statement.setString(1, role)
            statement.executeQuery().use { result -> result.next(); result.getBoolean(1) }
        }

    private fun assertCompatibleAttackerTriggerRejected(connection: Connection, studyId: UUID) {
        assertThrows(SQLException::class.java) {
            inRoleTransaction(connection, MIGRATION_ROLE) {
                it.createStatement().use { statement ->
                    statement.execute(
                        "CREATE TABLE forged_studies (study_id UUID PRIMARY KEY, settings JSONB NOT NULL)",
                    )
                    statement.execute(
                        "CREATE TRIGGER forge_revision AFTER INSERT OR UPDATE OF settings ON forged_studies " +
                            "FOR EACH ROW EXECUTE FUNCTION record_data_collection_settings_revision()",
                    )
                }
                it.prepareStatement("INSERT INTO forged_studies VALUES (?, 'revision fixture', ?::jsonb)").use { statement ->
                    statement.setObject(1, studyId)
                    statement.setString(2, "{\"DataCollection\":${dataCollectionSettings(99, enabled = true)}}")
                    statement.executeUpdate()
                }
            }
        }
        assertEquals(0, ledgerCount(connection, studyId, 99))
    }

    private fun assertDirectInsertDenied(connection: Connection, role: String, studyId: UUID, version: Int) {
        assertThrows(SQLException::class.java) {
            inRoleTransaction(connection, role) {
                it.prepareStatement(
                    "INSERT INTO data_collection_settings_revisions (study_id, settings_version, setting) " +
                        "VALUES (?, ?, ?::jsonb)",
                ).use { statement ->
                    statement.setObject(1, studyId)
                    statement.setInt(2, version)
                    statement.setString(3, dataCollectionSettings(version, enabled = true))
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun assertMutationDeniedForRuntimeAdmin(connection: Connection, operation: String, studyId: UUID) {
        val sql = when (operation) {
            "UPDATE" -> "UPDATE data_collection_settings_revisions SET issued_at = now() WHERE study_id = '$studyId'"
            "DELETE" -> "DELETE FROM data_collection_settings_revisions WHERE study_id = '$studyId'"
            "TRUNCATE" -> "TRUNCATE data_collection_settings_revisions"
            else -> error("unsupported operation")
        }
        assertThrows(SQLException::class.java) {
            inRoleTransaction(connection, "chronicle_admin") { it.createStatement().use { statement -> statement.execute(sql) } }
        }
    }

    private fun <T> inRoleTransaction(connection: Connection, role: String, block: (Connection) -> T): T {
        connection.autoCommit = false
        try {
            connection.createStatement().use { it.execute("SET LOCAL ROLE $role") }
            val result = block(connection)
            connection.commit()
            return result
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        } finally {
            connection.autoCommit = true
        }
    }

    private fun ledgerCountAsRole(
        connection: Connection,
        role: String,
        studyId: UUID,
        version: Int,
    ): Int = inRoleTransaction(connection, role) { restrictedConnection ->
        ledgerCount(restrictedConnection, studyId, version)
    }

    private fun ledgerCount(connection: Connection, studyId: UUID, version: Int): Int =
        connection.prepareStatement(
            "SELECT count(*) FROM data_collection_settings_revisions WHERE study_id = ? AND settings_version = ?",
        ).use {
            it.setObject(1, studyId)
            it.setInt(2, version)
            it.executeQuery().use { result -> result.next(); result.getInt(1) }
        }

    private fun ledgerCountForStudy(connection: Connection, studyId: UUID): Int =
        connection.prepareStatement(
            "SELECT count(*) FROM data_collection_settings_revisions WHERE study_id = ?",
        ).use {
            it.setObject(1, studyId)
            it.executeQuery().use { result -> result.next(); result.getInt(1) }
        }

    private fun studyCount(connection: Connection, studyId: UUID): Int =
        connection.prepareStatement("SELECT count(*) FROM studies WHERE study_id = ?").use {
            it.setObject(1, studyId)
            it.executeQuery().use { result -> result.next(); result.getInt(1) }
        }

    private fun dataCollectionSettings(version: Int, enabled: Boolean): String =
        "{\"settingsVersion\":$version,\"enabled\":$enabled}"
}
