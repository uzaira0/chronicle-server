package com.openlattice.chronicle.observability

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.impl.Log4jLogEvent
import org.apache.logging.log4j.core.layout.PatternLayout
import org.apache.logging.log4j.message.SimpleMessage
import org.apache.logging.log4j.util.SortedArrayStringMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class StructuredLoggingConfigurationTest {

    @Test
    fun `self-host JSON console envelope is valid and carries only explicit context fields`() {
        val resource = requireNotNull(javaClass.classLoader.getResourceAsStream("log4j2.xml"))
        val document = resource.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        val consoles = document.getElementsByTagName("Console")
        val jsonConsole = (0 until consoles.length)
            .map { consoles.item(it) }
            .first { it.attributes.getNamedItem("name").nodeValue == "JsonConsole" }
        val layouts = jsonConsole.childNodes
        val pattern = (0 until layouts.length)
            .map { layouts.item(it) }
            .first { it.nodeName == "PatternLayout" }
            .attributes.getNamedItem("pattern").nodeValue

        val context = SortedArrayStringMap().apply {
            putValue("requestId", "request-safe-1")
            putValue("httpMethod", "POST")
            putValue("httpPath", "/chronicle/v3/study/{id}")
        }
        val event = Log4jLogEvent.newBuilder()
            .setLoggerName("chronicle.fixture")
            .setLevel(Level.ERROR)
            .setMessage(SimpleMessage("quoted=\"value\"\nsecond-line"))
            .setContextData(context)
            .build()
        val rendered = PatternLayout.newBuilder().withPattern(pattern).build().toSerializable(event)
        val json = ObjectMapper().readTree(rendered)

        assertEquals("backend", json["service"].asText())
        assertEquals("ERROR", json["severity"].asText())
        assertEquals("request-safe-1", json["request_id"].asText())
        assertEquals("POST", json["method"].asText())
        assertEquals("/chronicle/v3/study/{id}", json["route"].asText())
        assertEquals("quoted=\"value\"\nsecond-line", json["message"].asText())
        assertFalse(json.has("studyId"))
        assertFalse(json.has("participantId"))
        assertFalse(json.has("userId"))
        assertFalse(json.has("clientIp"))
    }
}
