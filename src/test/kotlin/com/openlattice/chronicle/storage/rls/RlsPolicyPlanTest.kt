package com.openlattice.chronicle.storage.rls

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import java.sql.Connection
import java.util.UUID

/**
 * Row policies must cost one evaluation per statement, not one plpgsql call per row: a study
 * read is a filter over every row the scan returns, so per-row helpers multiply export time.
 */
class RlsPolicyPlanTest {
    companion object {
        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle_rls_plan")

        private val PER_ROW_HELPERS = listOf(
            "chronicle_participant_data_visible(",
            "chronicle_participant_processing_allowed(",
            "chronicle_is_deletion_worker(",
        )

        @BeforeClass
        @JvmStatic
        fun migrate() {
            ChronicleContractTestSchema.resetSchemaAndMigrations(postgres)
            postgres.createConnection("").use { connection ->
                connection.createStatement().use {
                    it.execute("CREATE ROLE $RLS_TEST_APP_ROLE NOSUPERUSER NOBYPASSRLS")
                    it.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO $RLS_TEST_APP_ROLE")
                    it.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO $RLS_TEST_APP_ROLE")
                }
            }
        }

        @AfterClass
        @JvmStatic
        fun clear() = ChronicleContractTestSchema.clearSchema(postgres)
    }

    private fun policyTables(connection: Connection): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT relname FROM pg_class
                WHERE relnamespace = 'public'::regnamespace AND relkind = 'r' AND relrowsecurity
                ORDER BY relname
                """.trimIndent(),
            ).use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
        }

    private fun scanFilters(connection: Connection, table: String): List<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("EXPLAIN SELECT * FROM public.$table").use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) else null }
                    .filter { it.trimStart().startsWith("Filter:") }
                    .toList()
            }
        }

    @Test
    fun participantDataPoliciesEvaluateTheirHelpersOncePerStatement() {
        postgres.createConnection("").use { connection ->
            connection.autoCommit = false
            try {
                val tables = policyTables(connection)
                assertTrue("expected participant data tables under row policies", "android_sensor_data" in tables)
                connection.applyLocalRlsTestContext(isAdmin = false, authorizedStudies = UUID.randomUUID().toString())
                val perRow = tables.associateWith { table ->
                    scanFilters(connection, table).filter { filter -> PER_ROW_HELPERS.any { it in filter } }
                }.filterValues { it.isNotEmpty() }
                assertEquals(emptyMap<String, List<String>>(), perRow)
            } finally {
                connection.rollback()
            }
        }
    }
}
