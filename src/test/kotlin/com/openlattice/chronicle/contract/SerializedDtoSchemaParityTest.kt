package com.openlattice.chronicle.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.CollectionAcknowledgmentEntry
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.study.StudyEncryptionSetting
import com.openlattice.chronicle.study.StudyUpdate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.reflect.full.primaryConstructor

class SerializedDtoSchemaParityTest {
    @Test
    fun sevenSchemasMatchSerializedPropertiesRequiredFieldsAndNullability() {
        val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
        val schemas = ObjectMapper(YAMLFactory()).readTree(File("../chronicle-api/chronicle.yaml"))["components"]["schemas"]
        val study = UUID.randomUUID()
        val samples = listOf(
            ParticipantStats(study, "P-1"), AndroidDeviceSensorAvailability(),
            AndroidSensorSample(UUID.randomUUID(), AndroidSensorType.values().first(), OffsetDateTime.now(), "UTC"),
            StudyUpdate(), CollectionModuleSetting(false),
            CollectionAcknowledgmentEntry(studyId = study, participantId = "P-1", sourceDeviceId = "phone", acknowledgedModules = emptySet(),
                acknowledgedAt = OffsetDateTime.now()),
            StudyEncryptionSetting(),
        )
        for (sample in samples) {
            val name = sample.javaClass.simpleName
            val json = mapper.valueToTree<JsonNode>(sample)
            val schema = schemas[name]
            assertEquals("$name serialized properties", json.fieldNames().asSequence().toSet(), schema["properties"].fieldNames().asSequence().toSet(
                ))
            val required = sample::class.primaryConstructor!!.parameters.filter { !it.isOptional }.mapNotNull { it.name }.toSet()
            assertEquals("$name required input", required, schema.path("required").map { it.asText() }.toSet())
            for (parameter in sample::class.primaryConstructor!!.parameters) {
                assertEquals("$name.${parameter.name} nullability", parameter.type.isMarkedNullable,
                    schema["properties"][parameter.name].path("nullable").asBoolean(false))
            }
            if (json.has("@class")) assertEquals("@class", schema.path("discriminator").path("propertyName").asText())
        }
    }
}
