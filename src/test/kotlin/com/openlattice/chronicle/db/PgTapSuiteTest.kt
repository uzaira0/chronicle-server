package com.openlattice.chronicle.db

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Statement
import org.junit.Assert.assertTrue
import org.junit.Test
import org.slf4j.LoggerFactory
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.testcontainers.images.builder.ImageFromDockerfile

class PgTapSuiteTest {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Test
    fun sqlContractsAndEveryPublicPlpgsqlFunctionPass() {
        val dockerfile = Path.of("src/test/docker/pgtap/Dockerfile")
        val image = ImageFromDockerfile()
            .withFileFromPath("Dockerfile", dockerfile)
            .withBuildArg("PROD_POSTGRES_IMAGE", ChronicleContractTestSchema.PROD_POSTGRES_IMAGE)
        val container = ChronicleContractTestSchema.prodPostgresContainer("chronicle_pgtap").apply {
            setImage(image)
            withCommand(
                "postgres", "-c", "fsync=off", "-c", "shared_preload_libraries=plpgsql_check",
                "-c", "plpgsql_check.profiler=on", "-c", "track_functions=all",
            )
        }
        container.use { postgres ->
            postgres.start()
            ChronicleContractTestSchema.waitForQueryReady(postgres)
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)
            postgres.createConnection("").use { connection ->
                connection.createStatement().use { statement ->
                    // Keep extension implementation functions outside the production public-function inventory.
                    statement.execute("CREATE SCHEMA test_extensions")
                    statement.execute("CREATE EXTENSION pgtap WITH SCHEMA test_extensions")
                    statement.execute("CREATE EXTENSION plpgsql_check WITH SCHEMA test_extensions")
                    statement.execute("GRANT USAGE ON SCHEMA test_extensions TO chronicle_app")
                    statement.execute("SET search_path = public, test_extensions")
                }
                val failures = mutableListOf<String>()
                val resources = PathMatchingResourcePatternResolver().getResources("classpath*:pgtap/*.sql")
                    .sortedBy { it.filename }
                check(resources.isNotEmpty()) { "No src/test/resources/pgtap/*.sql files found" }
                for (resource in resources) {
                    val file = resource.filename ?: resource.description
                    val sql = resource.inputStream.bufferedReader().use { it.readText() }
                    sql.lineSequence().filter { it.startsWith("-- SKIPPED:") }.forEach { logger.warn("{} {}", file, it) }
                    connection.autoCommit = false
                    try {
                        failures += validateTap(file, executeTap(connection, sql))
                    } catch (failure: Exception) {
                        failures += "$file: SQL execution failed: ${failure.message}"
                    } finally {
                        connection.rollback()
                        connection.autoCommit = true
                    }
                }
                failures += checkFunctionsAndWriteCoverage(connection)
                assertTrue(failures.joinToString("\n"), failures.isEmpty())
            }
        }
    }

    private fun executeTap(connection: Connection, sql: String): List<String> {
        val lines = mutableListOf<String>()
        connection.createStatement().use { statement ->
            var hasResult = statement.execute(sql)
            while (true) {
                if (hasResult) {
                    statement.resultSet.use { result ->
                        while (result.next()) {
                            result.getString(1)?.lineSequence()?.forEach { lines += it }
                        }
                    }
                } else if (statement.updateCount == -1) {
                    break
                }
                hasResult = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT)
            }
        }
        return lines
    }

    private fun validateTap(file: String, lines: List<String>): List<String> {
        val failures = mutableListOf<String>()
        val plans = lines.mapNotNull { Regex("^1\\.\\.(\\d+)(?:\\s.*)?$").matchEntire(it)?.groupValues?.get(1)?.toInt() }
        val tests = lines.mapNotNull { Regex("^(not ok|ok)\\s+(\\d+)(?:\\s.*)?$").matchEntire(it) }
        lines.filter { it.startsWith("not ok") || it.startsWith("Bail out!") }.forEach {
            failures += "$file: $it"
        }
        if (failures.isNotEmpty()) {
            lines.filter { it.startsWith("not ok") || it.startsWith("#") }.forEach {
                logger.warn("pgTAP {}: {}", file, it)
            }
        }
        if (plans.size != 1 || plans.singleOrNull() != tests.size) {
            failures += "$file: TAP plan mismatch: plans=$plans, executed=${tests.size}"
        }
        tests.forEachIndexed { index, test ->
            if (test.groupValues[2].toInt() != index + 1) {
                failures += "$file: TAP test number mismatch: ${test.value}"
            }
        }
        return failures
    }

    private fun checkFunctionsAndWriteCoverage(connection: Connection): List<String> {
        val failures = mutableListOf<String>()
        val functions = mutableListOf<Pair<Long, String>>()
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT p.oid, p.oid::regprocedure::text
                FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                JOIN pg_language l ON l.oid = p.prolang
                WHERE n.nspname = 'public' AND l.lanname = 'plpgsql'
                ORDER BY p.oid::regprocedure::text
                """.trimIndent(),
            ).use { result -> while (result.next()) functions += result.getLong(1) to result.getString(2) }
        }
        check(functions.isNotEmpty()) { "No public plpgsql functions found after Flyway bootstrap" }
        val reportDirectory = Path.of("build/reports")
        Files.createDirectories(reportDirectory)
        Files.newBufferedWriter(reportDirectory.resolve("plpgsql-coverage.tsv")).use { report ->
            report.write("function\tplpgsql_coverage_statements\tplpgsql_coverage_branches\n")
            for ((oid, function) in functions) {
                val triggers = mutableListOf<Triple<Long, String?, String?>>()
                connection.prepareStatement(
                    "SELECT DISTINCT tgrelid, tgoldtable, tgnewtable FROM pg_trigger WHERE tgfoid = ?::oid",
                ).use { statement ->
                    statement.setLong(1, oid)
                    statement.executeQuery().use { result ->
                        while (result.next()) triggers += Triple(result.getLong(1), result.getString(2), result.getString(3))
                    }
                }
                if (triggers.isEmpty()) triggers += Triple(0L, null, null)
                for ((relation, oldTable, newTable) in triggers) {
                    try {
                        connection.prepareStatement(
                            """
                            SELECT lineno, statement, sqlstate, message, level
                            FROM test_extensions.plpgsql_check_function_tb(
                                ?::regprocedure, relid => ?::oid::regclass, fatal_errors => false,
                                oldtable => ?, newtable => ?)
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, function)
                            statement.setLong(2, relation)
                            statement.setString(3, oldTable)
                            statement.setString(4, newTable)
                            statement.executeQuery().use { result ->
                                while (result.next()) {
                                    val detail = "$function:${result.getInt("lineno")}: ${result.getString("message")} " +
                                        "[${result.getString("sqlstate")}, ${result.getString("statement")}]"
                                    if (result.getString("level").equals("error", ignoreCase = true)) failures += detail
                                    else logger.warn("plpgsql_check: {}", detail)
                                }
                            }
                        }
                    } catch (failure: Exception) {
                        failures += "$function: plpgsql_check failed: ${failure.message}"
                    }
                }
                try {
                    connection.prepareStatement(
                        "SELECT test_extensions.plpgsql_coverage_statements(?::regprocedure), " +
                            "test_extensions.plpgsql_coverage_branches(?::regprocedure)",
                    ).use { statement ->
                        statement.setString(1, function)
                        statement.setString(2, function)
                        statement.executeQuery().use { result ->
                            check(result.next()) { "No coverage result for $function" }
                            report.write("$function\t${result.getString(1)}\t${result.getString(2)}\n")
                        }
                    }
                } catch (failure: Exception) {
                    failures += "$function: coverage query failed: ${failure.message}"
                }
            }
        }
        return failures
    }
}
