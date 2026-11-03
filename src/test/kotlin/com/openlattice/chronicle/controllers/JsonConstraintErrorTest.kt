package com.openlattice.chronicle.controllers

import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import com.fasterxml.jackson.module.kotlin.readValue
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.configuration.JacksonSecurityConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.http.MockHttpInputMessage

class JsonConstraintErrorTest : ChronicleServerExceptionHandlerTestSupport() {
    @Test
    fun typedSensorArrayReportsConstraintFailureRatherThanAnUnrelatedSchemaFailure() {
        val mapper = JacksonSecurityConfig().secureObjectMapper()
        val depth = mapper.factory.streamReadConstraints().maxNestingDepth
        val payload = "[{\"padding\":" + "{\"a\":".repeat(depth) + "0" + "}".repeat(depth) + "}]"
        assertEquals(emptyList<AndroidSensorSample>(), mapper.readValue<List<AndroidSensorSample>>("[]"))
        val failure = assertThrows(Exception::class.java) { mapper.readValue<List<AndroidSensorSample>>(payload) }
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is StreamConstraintsException })
        val requestBody = MockHttpInputMessage(payload.toByteArray())
        val error = handler.handleIllegalArgumentException(mockRequest(),
            HttpMessageNotReadableException("Invalid JSON", failure, requestBody))
        assertEquals(RequestErrorResponses.JSON_CONSTRAINT_EXCEEDED, error.body?.code)

        val unbounded = mapper.copy()
        unbounded.factory.setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(Int.MAX_VALUE).build())
        val schemaFailure = assertThrows(Exception::class.java) { unbounded.readValue<List<AndroidSensorSample>>(payload) }
        val control = handler.handleIllegalArgumentException(mockRequest(),
            HttpMessageNotReadableException("Invalid JSON", schemaFailure, requestBody))
        assertNull(control.body?.code)
    }
}
