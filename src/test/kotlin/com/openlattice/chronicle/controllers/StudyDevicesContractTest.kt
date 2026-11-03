package com.openlattice.chronicle.controllers

import com.geekbeast.mappers.mappers.ObjectMappers
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.util.tests.TestDataFactory
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class StudyDevicesContractTest : ChronicleServerTests() {
    @Test fun deviceMetadataIsAnObjectAndOnlyAppInstancesAreReported() {
        val mapper = ObjectMappers.getJsonMapper()
        val expected = checkNotNull(javaClass.getResourceAsStream("/web-golden/backend-study-devices.json")).use { mapper.readTree(it) }
        (expected as ObjectNode).remove("_comment")
        val study = clientUser1.studyApi.createStudy(Study(title = "device contract", contact = "test@example.org"))
        hds.connection.use { connection ->
            expected.fields().forEachRemaining { (participant, devices) ->
                clientUser1.studyApi.registerParticipant(study, TestDataFactory.participant().copy(participantId = participant))
                for (device in devices) connection.prepareStatement("""
                    INSERT INTO devices (study_id, participant_id, random_device_id, device_type, source_device)
                    VALUES (?, ?, ?, ?, ?::jsonb)
                """.trimIndent()).use {
                    it.setObject(1, study); it.setString(2, participant)
                    it.setObject(3, UUID.fromString(device.path("randomDeviceId").asText()))
                    it.setString(4, device.path("deviceType").asText())
                    it.setString(5, mapper.writeValueAsString(device.path("sourceDevice")))
                    it.executeUpdate()
                }
            }
        }
        assertEquals(expected, mapper.valueToTree<JsonNode>(clientUser1.studyApi.getStudyDevices(study)))
    }
}
