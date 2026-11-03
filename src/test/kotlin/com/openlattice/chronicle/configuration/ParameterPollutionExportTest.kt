package com.openlattice.chronicle.configuration

import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.controllers.SynchronousExportRequest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID

class ParameterPollutionExportTest {
    private val path = "${StudyApi.BASE}/${UUID.randomUUID()}${StudyApi.PARTICIPANTS_PATH}${StudyApi.DATA_PATH}"

    @Test fun collectionValuedExportFieldsHaveNoParticipantCountCap() {
        for (uri in listOf(path, path.removePrefix(StudyApi.SERVICE), path.replace(StudyApi.CONTROLLER, "/api/web/study"))) {
            for (method in listOf("GET", "POST")) {
                for (name in listOf(StudyApi.PARTICIPANT_ID, SynchronousExportRequest::sensorTypes.name)) {
                    assertEquals(200, status(method, uri, name, ParameterPollutionConfig.MAX_ALLOWED_DUPLICATES + 1))
                }
            }
        }
    }

    @Test fun scalarDuplicatesAndParticipantDuplicatesOnOtherRoutesAreRejected() {
        for (name in listOf(StudyApi.DATA_TYPE, StudyApi.START_DATE, StudyApi.END_DATE, StudyApi.RESPONSE_TYPE)) {
            assertEquals(400, status("GET", path, name, 2))
        }
        assertEquals(400, status("GET", "$path/other", StudyApi.PARTICIPANT_ID, 2))
        assertEquals(400, status("GET", path.substringBefore(StudyApi.PARTICIPANTS_PATH) + "/android/upload-diagnostics", StudyApi.PARTICIPANT_ID, 2))
    }

    /**
     * List-shaped parameters (`ids`, `fields`, `ids[]`, `item[0]`, `filters[name]`) may repeat, but only up to the cap;
     * past it the request is refused with the parameter named, before any controller runs.
     */
    @Test fun listParametersRepeatUpToTheCapAndAreRefusedPastIt() {
        val cap = ParameterPollutionConfig.MAX_ALLOWED_DUPLICATES
        for (name in listOf("ids", "IDS", "fields", "participantIds[]", "item[0]", "filters[name]")) {
            assertEquals(name, 200, status("GET", "/chronicle/v3/study", name, cap))
            val request = MockHttpServletRequest("GET", "/chronicle/v3/study").apply {
                addParameter(name, *Array(cap + 1) { "value-$it" })
            }
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()
            ParameterPollutionConfig().parameterPollutionFilter().doFilter(request, response, chain)
            assertEquals(name, 400, response.status)
            assertEquals(name, "Too many values for parameter: $name", response.errorMessage)
            assertEquals(name, null, chain.request)
        }
        assertEquals(400, status("GET", "/chronicle/v3/study", "item[x1]", 2))
    }

    /** A body the container cannot parse into parameters is left to the endpoint's own validation, not failed here. */
    @Test fun unreadableParametersPassThroughToTheEndpoint() {
        val request = object : jakarta.servlet.http.HttpServletRequestWrapper(MockHttpServletRequest("POST", "/chronicle/v3/study")) {
            override fun getParameterMap(): Map<String, Array<String>> = error("parameter parse failed")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        ParameterPollutionConfig().parameterPollutionFilter().doFilter(request, response, chain)
        assertEquals(200, response.status)
        assertEquals(request, chain.request)
    }

    private fun status(method: String, uri: String, parameter: String, count: Int): Int {
        val request = MockHttpServletRequest(method, uri).apply {
            addParameter(parameter, *Array(count) { "value-$it" })
        }
        val response = MockHttpServletResponse()
        ParameterPollutionConfig().parameterPollutionFilter().doFilter(request, response, MockFilterChain())
        return response.status
    }
}
