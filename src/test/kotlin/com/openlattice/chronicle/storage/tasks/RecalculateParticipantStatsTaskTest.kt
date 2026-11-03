package com.openlattice.chronicle.storage.tasks

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.UsageEnrollmentFixtures.enrollUsageFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.prepareUsageFixture
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.util.tests.TestDataFactory
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/** A stored zone id PostgreSQL does not know never stops the stats of its study or of later studies. */
class RecalculateParticipantStatsTaskTest : ChronicleServerTests() {
    private val noon = OffsetDateTime.now(ZoneOffset.UTC).withHour(12).withMinute(0).withSecond(0).withNano(0)

    private fun studyWithUsage(title: String, vararg rows: Pair<OffsetDateTime, String>): Pair<UUID, String> {
        val studyId = clientUser1.studyApi.createStudy(Study(title = title, contact = "tests@openlattice.com"))
        prepareUsageFixture(clientUser1, studyId)
        val participant = TestDataFactory.participant(ParticipationStatus.ENROLLED)
        clientUser1.studyApi.registerParticipant(studyId, participant)
        val enrollment = enrollUsageFixture(studyId, participant.participantId, TestDataFactory.androidDevice())
        val template = TestDataFactory.chronicleUsageEvents(studyId, participant.participantId, count = 1)
            .single() as ChronicleUsageEvent
        val events = rows.map { (timestamp, zone) ->
            template.copy(timestamp = timestamp, timezone = zone, clientEventId = UUID.randomUUID())
        }
        enrollment.mobileApi.uploadAndroidUsageEventDataV4(studyId, participant.participantId,
            enrollment.replay.device.randomDeviceId, ChronicleData(events))
        return studyId to participant.participantId
    }

    @Test
    fun anUnknownZoneRowIsSkippedAndEveryStudyGetsItsStats() {
        val valid = noon.minusDays(2)
        val (first, firstParticipant) = studyWithUsage("Stats with an unknown zone",
            noon.minusDays(4) to "Mars/Olympus_Mons", valid to "America/Santiago")
        val (second, secondParticipant) = studyWithUsage("Stats after an unknown zone", valid to "America/Santiago")
        RLSRequestContext.withSystemContext {
            testServer.context.getBean(AppDataUploadService::class.java).drainEventStorage()
        }

        val written = mutableListOf<ParticipantStats>()
        val real = testServer.context.getBean(StudyManager::class.java)
        val recording = object : StudyManager by real {
            override fun insertOrUpdateParticipantStats(stats: ParticipantStats) { written += stats }
        }
        RLSRequestContext.withSystemContext {
            RecalculateParticipantStatsTask().recalculateParticipantStats(
                recording, listOf(first, second), sr.getDefaultEventStorage().dataSource)
        }

        val santiagoDate = valid.atZoneSameInstant(ZoneId.of("America/Santiago")).toLocalDate()
        assertEquals(
            mapOf(firstParticipant to setOf(santiagoDate), secondParticipant to setOf(santiagoDate)),
            written.filter { it.androidUniqueDates.isNotEmpty() }.associate { it.participantId to it.androidUniqueDates },
        )
    }
}
