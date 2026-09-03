package com.openlattice.chronicle.e2e.dsl.assertions

import org.junit.Assert.assertTrue

object EnrollmentAssertions {

    fun assertParticipantEnrolled(devices: Map<String, List<Map<String, Any>>>, participantId: String) {
        assertTrue(
            "Expected participant $participantId in study devices but was not found",
            devices.containsKey(participantId)
        )
    }

    fun assertDeviceCount(devices: Map<String, List<Map<String, Any>>>, participantId: String, atLeast: Int) {
        val count = devices[participantId]?.size ?: 0
        assertTrue(
            "Expected at least $atLeast device(s) for participant $participantId, but got $count",
            count >= atLeast
        )
    }
}
