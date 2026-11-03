package com.openlattice.chronicle.configuration

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.geekbeast.mappers.mappers.ObjectMappers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ChronicleConfigurationTest {
    private val mapper = ObjectMappers.getYamlMapper().copy()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    @Test
    fun `shipped and test configuration contain only consumed settings`() {
        listOf("src/main/resources/chronicle.yaml", "src/test/resources/chronicle.yaml").forEach { path ->
            val text = Files.readString(Path.of(path))
            val tree = mapper.readTree(text)
            assertEquals(setOf("storageConfiguration"), tree.fieldNames().asSequence().toSet())
            assertEquals(ChronicleStorageConfiguration(), mapper.readValue(text, ChronicleConfiguration::class.java).storageConfiguration)
        }
    }

    @Test
    fun `obsolete configuration fields are refused instead of silently ignored`() {
        assertThrows(UnrecognizedPropertyException::class.java) {
            mapper.readValue("storageConfiguration: {}\nbucketName: obsolete\n", ChronicleConfiguration::class.java)
        }
    }
}
