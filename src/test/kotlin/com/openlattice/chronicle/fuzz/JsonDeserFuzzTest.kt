package com.openlattice.chronicle.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.configuration.JacksonSecurityConfig
import java.io.IOException

/**
 * Fuzz tests for the production (hardened) ObjectMapper reading the pilot upload bodies.
 *
 * Goals:
 * - Each upload DTO, and the raw tree, fails only with JsonProcessingException / IOException, which the
 *   controller advice turns into a 400; DTO validation failures surface wrapped in a JsonMappingException
 * - No crash, hang, or OOM on arbitrary byte input
 */
class JsonDeserFuzzTest {

    private val mapper = JacksonSecurityConfig().secureObjectMapper()

    private val uploadBodies = listOf(
        object : TypeReference<ChronicleData>() {},
        object : TypeReference<List<BatterySample>>() {},
        object : TypeReference<List<AndroidSensorSample>>() {},
        object : TypeReference<List<AndroidUploadDiagnosticEvent>>() {},
        object : TypeReference<AndroidDeviceSensorAvailability>() {},
    )

    @FuzzTest(maxDuration = "30s")
    fun uploadBodiesFailOnlyAsJsonErrors(data: FuzzedDataProvider) {
        val target = data.consumeInt(0, uploadBodies.size)
        val bytes = data.consumeRemainingAsBytes()
        try {
            if (target == uploadBodies.size) mapper.readTree(bytes) else mapper.readValue(bytes, uploadBodies[target])
        } catch (_: JsonProcessingException) {
            // expected for malformed JSON and invalid DTO values
        } catch (_: IOException) {
            // expected for truncated input
        }
    }
}
