package com.openlattice.chronicle.serialization

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.readValue
import com.geekbeast.mappers.mappers.ObjectMappers
import com.google.common.base.Optional
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.sources.IOSDevice
import com.openlattice.chronicle.sources.SourceDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDeviceSerializationTest {
    private val mapper = ObjectMappers.getJsonMapper()
    private val optionalSourceDeviceType = object : TypeReference<Optional<SourceDevice>>() {}

    @Test
    fun testAndroidSourceDeviceOptionalRoundTrip() {
        val original: Optional<SourceDevice> = Optional.of(
            AndroidDevice(
                device = "Samsung",
                model = "P",
                codename = "Chocolate Chip",
                brand = "Samsung",
                osVersion = "21",
                sdkVersion = "21",
                product = "product",
                deviceId = "device-id"
            )
        )

        val json = mapper.writerFor(optionalSourceDeviceType).writeValueAsString(original)
        assertTrue("Polymorphic SourceDevice JSON must carry a class marker", json.contains("\"@class\""))
        assertTrue(json.contains("AndroidDevice"))

        val result: Optional<SourceDevice> = mapper.readValue(json, optionalSourceDeviceType)
        assertTrue(result.isPresent)
        assertEquals(original.get(), result.get())
    }

    @Test
    fun testIosSourceDeviceOptionalRoundTrip() {
        val original: Optional<SourceDevice> = Optional.of(
            IOSDevice(
                name = "iPhone",
                systemName = "iOS",
                model = "iPhone 15",
                localizedModel = "iPhone",
                version = "17.2",
                deviceId = "ios-device-id",
                apnDeviceToken = "apn-token"
            )
        )

        val json = mapper.writerFor(optionalSourceDeviceType).writeValueAsString(original)
        assertTrue("Polymorphic SourceDevice JSON must carry a class marker", json.contains("\"@class\""))
        assertTrue(json.contains("IOSDevice"))

        val result: Optional<SourceDevice> = mapper.readValue(json, optionalSourceDeviceType)
        assertTrue(result.isPresent)
        assertEquals(original.get(), result.get())
    }
}
