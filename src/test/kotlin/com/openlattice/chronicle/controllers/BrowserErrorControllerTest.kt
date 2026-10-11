package com.openlattice.chronicle.controllers

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.observability.ChronicleMetrics
import org.junit.Assert.assertEquals
import org.junit.Test
import org.springframework.mock.web.MockHttpServletRequest

class BrowserErrorControllerTest {
    @Test fun boundedRedactedFailuresIncrementOnlyFiniteMetrics() {
        val controller = BrowserErrorController(ObjectMappers.newJsonMapper())
        val counter = ChronicleMetrics.browserErrorsTotal.labels("ERROR")
        val before = counter.get()
        fun send(body: String) = controller.report(MockHttpServletRequest().apply { setContent(body.toByteArray()) }).statusCode.value()
        assertEquals(204, send("""{"kind":"ERROR"}"""))
        assertEquals(before + 1, counter.get(), 0.0)
        assertEquals(400, send("""{"kind":"ERROR","message":"patient sentinel"}"""))
        assertEquals(400, send("""{"kind":"patient sentinel"}"""))
        assertEquals(400, send("""{"kind":null}"""))
        assertEquals(400, send("null"))
        assertEquals(400, send("{}"))
        assertEquals(400, send("""{"kind":"ERROR"} {}"""))
        assertEquals(413, send(" ".repeat(BrowserErrorController.MAX_REPORT_BYTES + 1)))
        assertEquals(before + 1, counter.get(), 0.0)
    }
}
