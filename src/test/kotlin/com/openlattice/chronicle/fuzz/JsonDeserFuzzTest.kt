package com.openlattice.chronicle.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException

/**
 * Fuzz tests for Jackson ObjectMapper deserialization.
 *
 * Goals:
 * - ObjectMapper.readTree never throws anything other than JsonProcessingException / IOException
 * - No crash, hang, or OOM on arbitrary byte input
 */
class JsonDeserFuzzTest {

    private val mapper = ObjectMapper()

    @FuzzTest(maxDuration = "30s")
    fun readTreeDoesNotCrash(data: FuzzedDataProvider) {
        val bytes = data.consumeRemainingAsBytes()
        try {
            mapper.readTree(bytes)
        } catch (_: JsonProcessingException) {
            // expected for malformed JSON
        } catch (_: IOException) {
            // expected for truncated input
        }
    }
}
