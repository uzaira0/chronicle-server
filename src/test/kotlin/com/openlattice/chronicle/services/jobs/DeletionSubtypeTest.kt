package com.openlattice.chronicle.services.jobs

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.deletion.DeleteParticipantActivityRecognitionEventsDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantAppNetworkUsageDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantConnectivityStateEventsDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantDeviceSettingsDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantHealthMetricsDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantSleepEventsDataRunner
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class DeletionSubtypeTest {
    @Test fun eachRegisteredDeletionRunnerAcceptsShortAndLegacyPayloads() {
        val runners = listOf(DeleteParticipantSleepEventsDataRunner(), DeleteParticipantConnectivityStateEventsDataRunner(),
            DeleteParticipantHealthMetricsDataRunner(), DeleteParticipantActivityRecognitionEventsDataRunner(),
            DeleteParticipantAppNetworkUsageDataRunner(), DeleteParticipantDeviceSettingsDataRunner())
        val mapper = ObjectMappers.newJsonMapper()
        val study = UUID.randomUUID()
        for (runner in runners) {
            for (name in listOf(runner.accepts().simpleName, runner.accepts().name)) {
                val definition = mapper.readValue("""{"@type":"$name","studyId":"$study","participantIds":["p1"]}""", ChronicleJobDefinition::class.java)
                assertEquals(runner.accepts(), definition.javaClass)
            }
        }
    }
}
