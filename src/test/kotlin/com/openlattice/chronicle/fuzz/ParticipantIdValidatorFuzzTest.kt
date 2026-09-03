package com.openlattice.chronicle.fuzz

import com.code_intelligence.jazzer.junit.FuzzTest
import com.openlattice.chronicle.util.validateParticipantId

/**
 * Fuzz tests for ParticipantIdValidator.
 *
 * Goals:
 * - No unexpected exceptions (only IllegalArgumentException from require())
 * - No input bypasses the alphanumeric+underscore+dot+dash constraint
 * - No input exceeds the 1..255 length bounds
 */
class ParticipantIdValidatorFuzzTest {

    /**
     * Regex mirrors the production validation pattern so we can cross-check.
     */
    private val validPattern = Regex("^[a-zA-Z0-9_.-]+$")

    @FuzzTest(maxDuration = "5m")
    fun fuzzValidateParticipantId(input: String) {
        try {
            validateParticipantId(input)
            // If validation passed, the input must conform to the constraints
            assert(input.length in 1..255) {
                "Accepted input with invalid length: ${input.length}"
            }
            assert(input.matches(validPattern)) {
                "Accepted input with invalid characters: $input"
            }
        } catch (expected: IllegalArgumentException) {
            // Expected for invalid input — require() throws IllegalArgumentException
        }
        // Any other exception type is a bug and will fail the fuzz test
    }

    @FuzzTest(maxDuration = "5m")
    fun fuzzSqlInjectionPatterns(data: ByteArray) {
        // Generate strings that include common SQL injection fragments
        val sqlPayloads = FuzzTestConstants.SQL_INJECTION_PAYLOADS
        val baseInput = String(data, Charsets.UTF_8)
        for (payload in sqlPayloads) {
            val combined = baseInput + payload
            try {
                validateParticipantId(combined)
                // If it passed, verify no SQL-special characters leaked through
                assert(combined.matches(validPattern)) {
                    "SQL injection pattern accepted: $combined"
                }
            } catch (expected: IllegalArgumentException) {
                // Expected rejection
            }
        }
    }
}
