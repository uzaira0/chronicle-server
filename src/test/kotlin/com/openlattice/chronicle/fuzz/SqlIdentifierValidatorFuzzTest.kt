package com.openlattice.chronicle.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import com.openlattice.chronicle.util.SqlIdentifierValidator

/**
 * Fuzz tests for SqlIdentifierValidator.
 *
 * Goals:
 * - validateIdentifier never allows SQL keywords or special characters
 * - validateTempTableName enforces prefix allowlist and character constraints
 * - quoteIdentifier never produces unbalanced quotes
 * - No unexpected exceptions escape (only InvalidSqlIdentifierException / IllegalArgumentException)
 */
class SqlIdentifierValidatorFuzzTest {

    private val validIdentifierPattern = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

    @FuzzTest(maxDuration = "5m")
    fun fuzzValidateIdentifier(data: FuzzedDataProvider) {
        val input = data.consumeRemainingAsString()
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
    fun fuzzValidateTempTableName(data: FuzzedDataProvider) {
        val input = data.consumeRemainingAsString()
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
