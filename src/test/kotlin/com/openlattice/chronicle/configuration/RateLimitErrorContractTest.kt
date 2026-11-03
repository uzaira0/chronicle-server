package com.openlattice.chronicle.configuration

import com.geekbeast.mappers.mappers.ObjectMappers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID

class RateLimitErrorContractTest : RateLimitFixture() {
    @Test
    fun rateLimitedErrorsPreserveRetryAfterInTheApiErrorEnvelope() {
        val filter = createFilter(RateLimitConfiguration(authRequestsPerMinute = 1,
            burstCapacityMultiplier = 1.0, hazelcastMapName = "RL_ERROR_${UUID.randomUUID()}"))
        filter.doFilter(createRequest(), MockHttpServletResponse(), MockFilterChain())
        val response = MockHttpServletResponse()
        filter.doFilter(createRequest(), response, MockFilterChain())
        val body = ObjectMappers.getJsonMapper().readTree(response.contentAsByteArray)
        assertEquals(429, body.path("status").asInt())
        assertEquals("Too Many Requests", body.path("error").asText())
        assertEquals(response.getHeader(RateLimitFilter.HEADER_RETRY_AFTER)?.toLong(), body.path("retryAfter").asLong())
    }
}
