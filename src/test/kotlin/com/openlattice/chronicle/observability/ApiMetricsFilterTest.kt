package com.openlattice.chronicle.observability

import org.junit.Assert.assertEquals
import org.junit.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HandlerMapping

class ApiMetricsFilterTest {
    @Test
    fun manyUnknownPathsCreateOnlyOneMetricChild() {
        val filter = ApiMetricsFilter()
        ChronicleMetrics.apiRequestDuration.remove(ApiMetricsFilter.UNMATCHED, "GET")
        repeat(1_000) { index ->
            val request = MockHttpServletRequest("GET", "/chronicle/v4/not-a-route/nonce$index")
            filter.doFilter(request, MockHttpServletResponse()) { _, response ->
                (response as MockHttpServletResponse).status = 404
            }
        }
        val samples = ChronicleMetrics.apiRequestDuration.collect().single().samples
        val unmatched = samples.filter { it.name.endsWith("_count") && ApiMetricsFilter.UNMATCHED in it.labelValues && "GET" in it.labelValues }
        assertEquals(1, unmatched.size)
        assertEquals(1_000.0, unmatched.single().value, 0.0)
        assertEquals(1, ChronicleMetrics.apiErrorsTotal.collect().single().samples.count { it.name.endsWith("_total") &&
            ApiMetricsFilter.UNMATCHED in it.labelValues && "GET" in it.labelValues && "404" in it.labelValues })
        ChronicleMetrics.apiRequestDuration.remove(ApiMetricsFilter.UNMATCHED, "GET")
        ChronicleMetrics.apiErrorsTotal.remove(ApiMetricsFilter.UNMATCHED, "GET", "404")
    }

    @Test
    fun recordsTheMatchedTemplateAfterDispatch() {
        val template = "/chronicle/v4/study/{studyId}/android/sensors/availability"
        ChronicleMetrics.apiRequestDuration.remove(template, "POST")
        ApiMetricsFilter().doFilter(MockHttpServletRequest("POST", "/chronicle/v4/study/private-id/android/sensors/availability"),
            MockHttpServletResponse()) { request, _ ->
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, template)
        }
        assertEquals(1.0, ChronicleMetrics.apiRequestDuration.collect().single().samples.single { it.name.endsWith("_count") &&
            template in it.labelValues }.value, 0.0)
        ChronicleMetrics.apiRequestDuration.remove(template, "POST")
    }
}
