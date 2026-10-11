package com.openlattice.chronicle.configuration

import com.fasterxml.jackson.databind.exc.InvalidTypeIdException
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.sources.SourceDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JacksonSecurityConfigTest {
    private val mapper = JacksonSecurityConfig().secureObjectMapper()

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

    @Test(expected = InvalidTypeIdException::class)
    fun `annotation driven polymorphism rejects subtypes outside Chronicle models`() {
        mapper.readValue(
            """{"@class":"example.UntrustedSourceDevice"}""",
            SourceDevice::class.java,
        )
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
            "device-id",
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
}
