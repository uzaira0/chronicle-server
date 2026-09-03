package com.openlattice.chronicle.fuzz

import com.code_intelligence.jazzer.junit.FuzzTest
import com.openlattice.chronicle.util.SqlIdentifierValidator

/**
 * Fuzz tests for SqlIdentifierValidator.
 *
 * Goals:
 * - validateIdentifier never allows SQL keywords or special characters
 * - validateTempTableName enforces prefix allowlist and character constraints
 * - validateImportTableName enforces its pattern
 * - quoteIdentifier never produces unbalanced quotes
 * - No unexpected exceptions escape (only InvalidSqlIdentifierException / IllegalArgumentException)
 */
class SqlIdentifierValidatorFuzzTest {

    private val validIdentifierPattern = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

    @FuzzTest(maxDuration = "5m")
    fun fuzzValidateIdentifier(input: String) {
        try {
            val result = SqlIdentifierValidator.validateIdentifier(input)
            // If validation passed, verify structural invariants
            assert(result == input) { "validateIdentifier mutated input" }
            assert(result.length <= 63) { "Accepted identifier exceeding 63 chars" }
            assert(result.matches(validIdentifierPattern)) {
                "Accepted identifier with invalid pattern: $result"
            }
        } catch (expected: SqlIdentifierValidator.InvalidSqlIdentifierException) {
            // Expected for invalid input
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun fuzzValidateTempTableName(input: String) {
        try {
            val result = SqlIdentifierValidator.validateTempTableName(input)
            assert(result == input) { "validateTempTableName mutated input" }
            assert(result.length <= 63) { "Accepted temp table name exceeding 63 chars" }
            // Must start with an allowed prefix
            val allowedPrefixes = listOf(
                "duplicate_events_", "duplicate_ios_events_", "temp_", "tmp_"
            )
            assert(allowedPrefixes.any { result.startsWith(it) }) {
                "Accepted temp table without allowed prefix: $result"
            }
        } catch (expected: SqlIdentifierValidator.InvalidSqlIdentifierException) {
            // Expected for invalid input
        } catch (expected: SecurityException) {
            // Expected
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun fuzzValidateImportTableName(input: String) {
        val importPattern = Regex("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)?$")
        try {
            val result = SqlIdentifierValidator.validateImportTableName(input)
            assert(result == input) { "validateImportTableName mutated input" }
            assert(result.length <= 255) { "Accepted import table name exceeding 255 chars" }
            assert(result.matches(importPattern)) {
                "Accepted import table name with invalid pattern: $result"
            }
        } catch (expected: SqlIdentifierValidator.InvalidSqlIdentifierException) {
            // Expected for invalid input
        } catch (expected: SecurityException) {
            // Expected
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun fuzzQuoteIdentifier(input: String) {
        try {
            val quoted = SqlIdentifierValidator.quoteIdentifier(input)
            // Quoted identifier must be wrapped in double quotes
            assert(quoted.startsWith("\"") && quoted.endsWith("\"")) {
                "quoteIdentifier produced unbalanced quotes: $quoted"
            }
            // The inner content should have no unescaped double quotes
            val inner = quoted.substring(1, quoted.length - 1)
            val unescapedQuoteCount = inner.replace("\"\"", "").count { it == '"' }
            assert(unescapedQuoteCount == 0) {
                "quoteIdentifier has unescaped quotes in: $quoted"
            }
        } catch (expected: SqlIdentifierValidator.InvalidSqlIdentifierException) {
            // Expected — input failed validation before quoting
        } catch (expected: SecurityException) {
            // Expected
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun fuzzSqlInjectionVectors(data: ByteArray) {
        val injections = FuzzTestConstants.SQL_INJECTION_PAYLOADS
        val base = String(data, Charsets.UTF_8)
        for (injection in injections) {
            val payload = base + injection
            try {
                SqlIdentifierValidator.validateIdentifier(payload)
                // If accepted, it must be safe
                assert(payload.matches(validIdentifierPattern)) {
                    "SQL injection payload accepted as identifier: $payload"
                }
            } catch (expected: SqlIdentifierValidator.InvalidSqlIdentifierException) {
                // Expected rejection
            } catch (expected: SecurityException) {
                // Expected
            }
        }
    }
}
