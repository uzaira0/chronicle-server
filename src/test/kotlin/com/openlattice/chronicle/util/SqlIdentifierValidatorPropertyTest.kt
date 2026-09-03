package com.openlattice.chronicle.util

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.codepoints
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.Test

private fun Arb.Companion.codepoints(chars: List<Char>): Arb<Codepoint> =
    Arb.element(chars).map { Codepoint(it.code) }

/**
 * Property-based tests for SqlIdentifierValidator to prevent SQL injection.
 */
class SqlIdentifierValidatorPropertyTest {

    // --- validateIdentifier ---

    @Test
    fun `valid identifiers starting with letter and containing only alphanumeric and underscore pass`() { runBlocking {
        val startChar = Arb.element(('a'..'z') + ('A'..'Z') + listOf('_'))
        val restChars = Arb.codepoints(('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('_'))
        forAll<Char, String>(startChar, Arb.string(0..30, restChars)) { first, rest ->
            val id = "$first$rest"
            // Skip SQL keywords which are legitimately rejected
            try {
                SqlIdentifierValidator.validateIdentifier(id)
                true
            } catch (e: SqlIdentifierValidator.InvalidSqlIdentifierException) {
                // Only acceptable if it is a reserved keyword
                e.message?.contains("reserved SQL keyword") == true
            }
        }
    } }

    @Test
    fun `blank identifiers are always rejected`() { runBlocking {
        forAll(Arb.element("", " ", "  ", "\t")) { id ->
            try {
                SqlIdentifierValidator.validateIdentifier(id)
                false
            } catch (_: SqlIdentifierValidator.InvalidSqlIdentifierException) {
                true
            }
        }
    } }

    @Test
    fun `identifiers exceeding 63 chars are always rejected`() { runBlocking {
        forAll(Arb.string(64..100, Codepoint.az())) { id ->
            try {
                SqlIdentifierValidator.validateIdentifier(id)
                false
            } catch (e: SqlIdentifierValidator.InvalidSqlIdentifierException) {
                e.message?.contains("maximum length") == true
            }
        }
    } }

    @Test
    fun `identifiers starting with digits are always rejected`() { runBlocking {
        forAll(Arb.int(0..9), Arb.string(1..20, Codepoint.az())) { digit, rest ->
            val id = "$digit$rest"
            try {
                SqlIdentifierValidator.validateIdentifier(id)
                false
            } catch (_: SqlIdentifierValidator.InvalidSqlIdentifierException) {
                true
            }
        }
    } }

    @Test
    fun `SQL injection payloads are always rejected`() { runBlocking {
        val injections = Arb.element(
            "'; DROP TABLE users--",
            "1 OR 1=1",
            "admin'--",
            "UNION SELECT * FROM passwords",
            "; DELETE FROM data",
            "table_name; --",
            "id\"; DROP TABLE",
            "col UNION ALL SELECT",
            "name' AND '1'='1"
        )
        forAll(injections) { payload ->
            try {
                SqlIdentifierValidator.validateIdentifier(payload)
                false
            } catch (_: SqlIdentifierValidator.InvalidSqlIdentifierException) {
                true
            }
        }
    } }

    @Test
    fun `SQL reserved keywords are rejected as identifiers`() { runBlocking {
        val keywords = Arb.element(
            "SELECT", "INSERT", "UPDATE", "DELETE", "DROP", "CREATE",
            "ALTER", "TABLE", "FROM", "WHERE", "UNION", "GRANT", "EXECUTE"
        )
        forAll(keywords) { keyword ->
            try {
                SqlIdentifierValidator.validateIdentifier(keyword)
                false
            } catch (e: SqlIdentifierValidator.InvalidSqlIdentifierException) {
                e.message?.contains("reserved SQL keyword") == true
            }
        }
    } }

    // --- validateTempTableName ---

    @Test
    fun `temp table names with valid prefix and alphanumeric suffix pass`() { runBlocking {
        val prefixes = Arb.element("duplicate_events_", "duplicate_ios_events_", "temp_", "tmp_")
        forAll(prefixes, Arb.string(1..20, Codepoint.alphanumeric())) { prefix, suffix ->
            // Filter out suffixes starting with uppercase to match the TEMP_TABLE_PATTERN (lowercase start)
            val safeSuffix = suffix.lowercase()
            try {
                SqlIdentifierValidator.validateTempTableName("$prefix$safeSuffix")
                true
            } catch (_: SqlIdentifierValidator.InvalidSqlIdentifierException) {
                false
            }
        }
    } }

    @Test
    fun `temp table names without valid prefix are rejected`() { runBlocking {
        forAll(Arb.string(1..30, Codepoint.az())) { name ->
            val hasValidPrefix = listOf("duplicate_events_", "duplicate_ios_events_", "temp_", "tmp_")
                .any { name.startsWith(it) }
            if (hasValidPrefix) true  // skip - has valid prefix
            else {
                try {
                    SqlIdentifierValidator.validateTempTableName(name)
                    false
                } catch (_: SqlIdentifierValidator.InvalidSqlIdentifierException) {
                    true
                }
            }
        }
    } }

    @Test
    fun `blank temp table names are rejected`() {
        try {
            SqlIdentifierValidator.validateTempTableName("")
            assert(false) { "Should have thrown" }
        } catch (_: SqlIdentifierValidator.InvalidSqlIdentifierException) {
            // expected
        }
    }

    // --- validateImportTableName ---

    @Test
    fun `unqualified and schema-qualified import table names with valid segments pass`() { runBlocking {
        val startChar = Arb.element(('a'..'z') + ('A'..'Z') + listOf('_'))
        val restChars = Arb.codepoints(('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('_'))
        forAll<Char, String>(startChar, Arb.string(0..20, restChars)) { first, rest ->
            val table = "$first$rest"
            val lower = table.lowercase()
            // pg_-prefixed / information_schema segments are intentionally rejected
            if (lower.startsWith("pg_") || lower == "information_schema") {
                true
            } else {
                try {
                    SqlIdentifierValidator.validateImportTableName(table) == table &&
                        SqlIdentifierValidator.validateImportTableName("src.$table") == "src.$table"
                } catch (_: SqlIdentifierValidator.InvalidSqlIdentifierException) {
                    false
                }
            }
        }
    } }

    @Test
    fun `known schema-qualified import sources are accepted`() {
        listOf("src.system_apps", "src.tud", "src.users", "public.foo", "system_apps").forEach {
            assert(SqlIdentifierValidator.validateImportTableName(it) == it) { "expected $it to be accepted" }
        }
    }

    @Test
    fun `unsafe import table names with dots, hyphens, system catalogs, or injection are rejected`() { runBlocking {
        forAll(Arb.element(
            "information_schema.columns",
            "pg_catalog.pg_authid",
            "pg_class",
            "src.tud.extra",
            "1src.table",
            "src.1table",
            "src..table",
            ".table",
            "table.",
            "src-staging.table",
            "foo-bar",
            "src.system_apps; DROP TABLE studies--",
            "src.system_apps--"
        )) { name ->
            try {
                SqlIdentifierValidator.validateImportTableName(name)
                false
            } catch (_: SqlIdentifierValidator.InvalidSqlIdentifierException) {
                true
            }
        }
    } }

    @Test
    fun `import table names exceeding 255 chars are rejected`() { runBlocking {
        forAll(Arb.string(256..300, Codepoint.az())) { name ->
            try {
                SqlIdentifierValidator.validateImportTableName(name)
                false
            } catch (e: SqlIdentifierValidator.InvalidSqlIdentifierException) {
                e.message?.contains("maximum length") == true
            }
        }
    } }

    // --- validateTimeout ---

    @Test
    fun `valid timeouts in range 0 to 3600000 always pass`() { runBlocking {
        forAll(Arb.long(0L..3600000L)) { timeout ->
            SqlIdentifierValidator.validateTimeout(timeout) == timeout
        }
    } }

    @Test
    fun `negative timeouts are always rejected`() { runBlocking {
        forAll(Arb.long(Long.MIN_VALUE..-1L)) { timeout ->
            try {
                SqlIdentifierValidator.validateTimeout(timeout)
                false
            } catch (_: IllegalArgumentException) {
                true
            }
        }
    } }

    @Test
    fun `timeouts exceeding 1 hour are always rejected`() { runBlocking {
        forAll(Arb.long(3600001L..7200000L)) { timeout ->
            try {
                SqlIdentifierValidator.validateTimeout(timeout)
                false
            } catch (_: IllegalArgumentException) {
                true
            }
        }
    } }

    // --- quoteIdentifier ---

    @Test
    fun `quoteIdentifier wraps valid identifiers in double quotes`() { runBlocking {
        val startChar = Arb.element(('a'..'z') + listOf('_'))
        val restChars = Arb.codepoints(('a'..'z') + ('0'..'9') + listOf('_'))
        forAll<Char, String>(startChar, Arb.string(0..20, restChars)) { first, rest ->
            val id = "$first$rest"
            try {
                val quoted = SqlIdentifierValidator.quoteIdentifier(id)
                quoted.startsWith("\"") && quoted.endsWith("\"") && quoted.contains(id)
            } catch (_: SqlIdentifierValidator.InvalidSqlIdentifierException) {
                // May be a keyword - acceptable
                true
            }
        }
    } }
}
