package com.openlattice.chronicle.storage.rls

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.ClassRule
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/** Real-PostgreSQL contracts for the V93 trigger owner, ACL, and legacy backfill boundary. */
class DataCollectionRevisionLedgerRlsTest {
    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_revision_rls_test")

        private const val MIGRATION_ROLE = "chronicle_revision_migrator_test"
        private val migration: String by lazy {
            requireNotNull(
                DataCollectionRevisionLedgerRlsTest::class.java.getResourceAsStream(
                    "/db/migration/V93__make_data_collection_revisions_transactional.sql",
                ),
            ).bufferedReader().use { it.readText() }
        }
    }

    @Test
    fun `non bypass migration owner can record through trigger while direct ledger writes stay denied`() {
        freshDatabase("revision_owner").use { connection ->
            installRoleAndTableShells(connection)
            val studyId = UUID.randomUUID()
            connection.prepareStatement("INSERT INTO studies (study_id, settings) VALUES (?, '{}'::jsonb)").use {
                it.setObject(1, studyId)
                assertEquals(1, it.executeUpdate())
            }

            applyMigrationAsRestrictedOwner(connection)
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

    @Test
    fun `ambiguous legacy revision aborts before current payload can become authority`() {
        freshDatabase("revision_ambiguity").use { connection ->
            installRoleAndTableShells(connection)
            val studyId = UUID.randomUUID()
            val revisionA = dataCollectionSettings(version = 11, enabled = false)
            val revisionB = dataCollectionSettings(version = 11, enabled = true)
            connection.prepareStatement("INSERT INTO studies (study_id, settings) VALUES (?, ?::jsonb)").use {
                it.setObject(1, studyId)
                it.setString(2, "{\"DataCollection\":$revisionB}")
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO study_settings_audit (study_id, setting_key, before_value, after_value)
                VALUES (?, 'DataCollection', NULL, ?::jsonb),
                       (?, 'DataCollection', NULL, ?::jsonb)
                """.trimIndent(),
            ).use {
                it.setObject(1, studyId)
                it.setString(2, revisionA)
                it.setObject(3, studyId)
                it.setString(4, revisionB)
                assertEquals(2, it.executeUpdate())
            }

            connection.autoCommit = false
            connection.createStatement().use { it.execute("SET LOCAL ROLE $MIGRATION_ROLE") }
            val failure = assertThrows(SQLException::class.java) {
                connection.createStatement().use { it.execute(migration) }
            }
            assertFalse(
                "migration must identify ambiguous authority instead of accepting current content",
                failure.message.orEmpty().contains("success", ignoreCase = true),
            )
            connection.rollback()
            connection.autoCommit = true

            connection.prepareStatement("SELECT to_regclass('public.data_collection_settings_revisions') IS NULL").use {
                it.executeQuery().use { result ->
                    result.next()
                    assertEquals(true, result.getBoolean(1))
                }
            }
        }
    }

    @Test
    fun `ambiguous historical revision is omitted after study advances to unambiguous current version`() {
        freshDatabase("revision_historical_ambiguity").use { connection ->
            installRoleAndTableShells(connection)
            val studyId = UUID.randomUUID()
            val ambiguousA = dataCollectionSettings(version = 11, enabled = false)
            val ambiguousB = dataCollectionSettings(version = 11, enabled = true)
            val current = dataCollectionSettings(version = 12, enabled = true)
            connection.prepareStatement("INSERT INTO studies (study_id, settings) VALUES (?, ?::jsonb)").use {
                it.setObject(1, studyId)
                it.setString(2, "{\"DataCollection\":$current}")
                assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement(
                """
                INSERT INTO study_settings_audit (study_id, setting_key, before_value, after_value)
                VALUES (?, 'DataCollection', NULL, ?::jsonb),
                       (?, 'DataCollection', NULL, ?::jsonb),
                       (?, 'DataCollection', NULL, ?::jsonb)
                """.trimIndent(),
            ).use {
                it.setObject(1, studyId)
                it.setString(2, ambiguousA)
                it.setObject(3, studyId)
                it.setString(4, ambiguousB)
                it.setObject(5, studyId)
                it.setString(6, current)
                assertEquals(3, it.executeUpdate())
            }

            applyMigrationAsRestrictedOwner(connection)

            assertEquals(0, ledgerCount(connection, studyId, 11))
            assertEquals(1, ledgerCount(connection, studyId, 12))
        }
    }

    @Test
    fun `legacy collection policy inserts without a synthetic revision while versioned policy is recorded`() {
        freshDatabase("revision_new_study").use { connection ->
            installRoleAndTableShells(connection)
            applyMigrationAsRestrictedOwner(connection)

            val legacyStudyId = UUID.randomUUID()
            connection.prepareStatement("INSERT INTO studies (study_id, settings) VALUES (?, ?::jsonb)").use {
                it.setObject(1, legacyStudyId)
                it.setString(
                    2,
                    """{"DataCollection":{"appUsageFrequency":"DAILY"}}""",
                )
                assertEquals(1, it.executeUpdate())
            }
            assertEquals(0, ledgerCountForStudy(connection, legacyStudyId))

            val malformedStudyId = UUID.randomUUID()
            assertThrows(SQLException::class.java) {
                connection.prepareStatement("INSERT INTO studies (study_id, settings) VALUES (?, ?::jsonb)").use {
                    it.setObject(1, malformedStudyId)
                    it.setString(2, """{"DataCollection":{"settingsVersion":null}}""")
                    it.executeUpdate()
                }
            }
            assertEquals(0, studyCount(connection, malformedStudyId))

            val versionedStudyId = UUID.randomUUID()
            connection.prepareStatement("INSERT INTO studies (study_id, settings) VALUES (?, ?::jsonb)").use {
                it.setObject(1, versionedStudyId)
                it.setString(2, "{\"DataCollection\":${dataCollectionSettings(version = 1, enabled = false)}}")
                assertEquals(1, it.executeUpdate())
            }
            assertEquals(1, ledgerCount(connection, versionedStudyId, version = 1))
        }
    }

    private fun freshDatabase(prefix: String): Connection {
        val database = "${prefix}_${UUID.randomUUID().toString().replace("-", "")}"
        postgres.createConnection("").use { admin ->
            admin.createStatement().use { it.execute("CREATE DATABASE $database") }
        }
        val jdbcUrl = postgres.jdbcUrl.substringBeforeLast('/') + "/$database"
        return DriverManager.getConnection(jdbcUrl, postgres.username, postgres.password)
    }

    private fun installRoleAndTableShells(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                DO ${'$'}${'$'} BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'chronicle_app') THEN
                        CREATE ROLE chronicle_app NOLOGIN NOSUPERUSER NOBYPASSRLS;
                    END IF;
                    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'chronicle_admin') THEN
                        CREATE ROLE chronicle_admin NOLOGIN NOSUPERUSER BYPASSRLS;
                    END IF;
                    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = '$MIGRATION_ROLE') THEN
                        CREATE ROLE $MIGRATION_ROLE NOLOGIN NOSUPERUSER NOBYPASSRLS;
                    END IF;
                END ${'$'}${'$'};
                """.trimIndent(),
            )
            statement.execute("GRANT USAGE, CREATE ON SCHEMA public TO $MIGRATION_ROLE")
            statement.execute(
                """
                CREATE FUNCTION chronicle_has_study_access(UUID)
                RETURNS BOOLEAN LANGUAGE sql STABLE AS 'SELECT true'
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE studies (
                    study_id UUID PRIMARY KEY,
                    settings JSONB NOT NULL DEFAULT '{}'::jsonb
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE study_settings_audit (
                    study_id UUID NOT NULL,
                    setting_key TEXT NOT NULL,
                    before_value JSONB,
                    after_value JSONB
                )
                """.trimIndent(),
            )
            statement.execute("ALTER TABLE studies OWNER TO $MIGRATION_ROLE")
            statement.execute("ALTER TABLE study_settings_audit OWNER TO $MIGRATION_ROLE")
        }
    }

    private fun applyMigrationAsRestrictedOwner(connection: Connection) {
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                statement.execute("SET LOCAL ROLE $MIGRATION_ROLE")
                statement.execute(migration)
            }
            connection.commit()
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        } finally {
            connection.autoCommit = true
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
                it.prepareStatement("INSERT INTO forged_studies VALUES (?, ?::jsonb)").use { statement ->
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
