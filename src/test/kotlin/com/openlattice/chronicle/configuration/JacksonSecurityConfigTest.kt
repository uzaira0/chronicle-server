package com.openlattice.chronicle.configuration

import com.fasterxml.jackson.databind.exc.InvalidTypeIdException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import com.openlattice.chronicle.organizations.Organization
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.sources.SourceDevice
import com.openlattice.chronicle.generated.UploadLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.InputStream
import java.util.Arrays

class JacksonSecurityConfigTest {
    private val mapper = JacksonSecurityConfig().secureObjectMapper()

    @Test
    fun `open study omits its end date and finite end dates round trip`() {
        val study = com.openlattice.chronicle.study.Study(title = "Open study", contact = "research@example.org")
        val json = mapper.writeValueAsString(study)
        org.junit.Assert.assertFalse(mapper.readTree(json).has("endedAt"))
        assertEquals(study, mapper.readValue(json, com.openlattice.chronicle.study.Study::class.java))
        val end = java.time.OffsetDateTime.parse("2026-10-22T12:00:00Z")
        val ended = com.openlattice.chronicle.study.Study(title = study.title, contact = study.contact, endedAt = end)
        val endedJson = mapper.writeValueAsString(ended)
        assertEquals(end.toInstant(), java.time.OffsetDateTime.parse(mapper.readTree(endedJson)["endedAt"].asText()).toInstant())
        assertEquals(ended, mapper.readValue(endedJson, com.openlattice.chronicle.study.Study::class.java))
    }

    @Test
    fun `mapper keeps Jackson string and nesting defaults`() {
        val defaults = StreamReadConstraints.defaults()
        assertEquals(defaults.maxStringLength, mapper.factory.streamReadConstraints().maxStringLength)
        assertEquals(defaults.maxNestingDepth, mapper.factory.streamReadConstraints().maxNestingDepth)
    }

    @Test
    fun `document parsing permits the transport byte limit and rejects larger input before building a tree`() {
        fun readWhitespaceDocument(target: com.fasterxml.jackson.databind.ObjectMapper, spaces: Long): Int =
            target.factory.createParser(whitespaceDocument(spaces)).use { parser -> parser.nextToken(); parser.intValue }
        assertEquals(0, readWhitespaceDocument(mapper, UploadLimits.MAX_REQUEST_SIZE_BYTES - 1))
        val oversized = UploadLimits.MAX_REQUEST_SIZE_BYTES * 2
        assertThrows(StreamConstraintsException::class.java) { readWhitespaceDocument(mapper, oversized) }
        val unbounded = mapper.copy()
        unbounded.factory.setStreamReadConstraints(unbounded.factory.streamReadConstraints().rebuild().maxDocumentLength(-1).build())
        assertEquals(0, readWhitespaceDocument(unbounded, oversized))
    }

    private fun whitespaceDocument(spaces: Long): InputStream = object : InputStream() {
        private var remaining = spaces
        private var valuePending = true
        override fun read(): Int = when {
            remaining > 0 -> { remaining--; ' '.code }
            valuePending -> { valuePending = false; '0'.code }
            else -> -1
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) {
                val value = read()
                if (value == -1) return -1
                bytes[offset] = value.toByte()
                return 1
            }
            val count = minOf(remaining, length.toLong()).toInt()
            Arrays.fill(bytes, offset, offset + count, ' '.code.toByte())
            remaining -= count
            return count
        }
    }

    @Test
    fun `production mapper rejects excessive untyped nesting while an unbounded control accepts it`() {
        val depth = mapper.factory.streamReadConstraints().maxNestingDepth + 1
        val json = "[".repeat(depth) + "0" + "]".repeat(depth)
        assertThrows(StreamConstraintsException::class.java) { mapper.readTree(json) }
        val unbounded = mapper.copy()
        unbounded.factory.setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(Int.MAX_VALUE).build())
        var value = unbounded.readTree(json)
        repeat(depth) { value = value[0] }
        assertEquals(0, value.asInt())
    }

    @Test
    fun `an organization body carrying free-form settings is refused`() {
        assertThrows(UnrecognizedPropertyException::class.java) {
            mapper.readValue("""{"title":"lab","settings":{"theme":"dark"}}""", Organization::class.java)
        }
    }

    @Test
    fun `annotation driven polymorphism rejects subtypes the model does not list`() {
        for (name in listOf("example.UntrustedSourceDevice", UnlistedSourceDevice::class.java.name)) {
            assertThrows(InvalidTypeIdException::class.java) {
                mapper.readValue("""{"@class":"$name"}""", SourceDevice::class.java)
            }
        }
    }

    @Test
    fun `annotation driven polymorphism accepts Chronicle model subtypes`() {
        val expected: SourceDevice = AndroidDevice(
            "device",
            "model",
            "codename",
            "brand",
            "14",
            "34",
            "product",
            "random-device-id",
        )

        val decoded = mapper.readValue(mapper.writeValueAsString(expected), SourceDevice::class.java)

        assertEquals(expected, decoded)
    }

    @Test
    fun `sets keep their json order`() {
        // A HashSet iterates "a" before "b"; stored settings must survive a read and write unchanged.
        val json = """["b","a"]"""
        val values = mapper.readValue(json, object : com.fasterxml.jackson.core.type.TypeReference<Set<String>>() {})

        assertEquals(json, mapper.writeValueAsString(values))
    }

    @Test
    fun `a repeated key cannot overwrite a field after the model init checked it`() {
        val valid = com.openlattice.chronicle.collection.CollectionAcknowledgment(
            acknowledgedModules = setOf(com.openlattice.chronicle.collection.CollectionModuleId.USAGE_EVENTS),
            acknowledgedAt = java.time.OffsetDateTime.parse("2026-08-17T12:00:00Z"),
            unavailableModules = setOf(com.openlattice.chronicle.collection.CollectionModuleId.SENSOR_ACCELEROMETER),
        )
        val json = mapper.writeValueAsString(valid)
        assertEquals(valid, mapper.readValue(json, valid::class.java))
        // Every creator key is present, so Jackson builds the object first and would then write the repeat into
        // the field directly, past `unavailableModules.all(isSensorModule)` in the init block.
        val repeated = json.removeSuffix("}") + ",\"unavailableModules\":[\"battery_telemetry\"]}"

        assertThrows(com.fasterxml.jackson.core.JsonParseException::class.java) {
            mapper.readValue(repeated, valid::class.java)
        }
    }

    /** A Chronicle class on the classpath is still not a device the wire may name. */
    class UnlistedSourceDevice : SourceDevice
}
