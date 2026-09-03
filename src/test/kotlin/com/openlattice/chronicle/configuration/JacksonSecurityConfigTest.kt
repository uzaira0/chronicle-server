package com.openlattice.chronicle.configuration

import com.fasterxml.jackson.databind.exc.InvalidTypeIdException
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.sources.SourceDevice
import org.junit.Assert.assertEquals
import org.junit.Test

class JacksonSecurityConfigTest {
    private val mapper = JacksonSecurityConfig().secureObjectMapper()

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
}
