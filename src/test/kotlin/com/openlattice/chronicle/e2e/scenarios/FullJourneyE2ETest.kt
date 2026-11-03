package com.openlattice.chronicle.e2e.scenarios

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.e2e.dsl.assertions.EnrollmentAssertions
import com.openlattice.chronicle.e2e.dsl.chronicleScenario
import com.openlattice.chronicle.e2e.dsl.di.ProvidersBundle
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.study.ParticipantDataType
import com.openlattice.chronicle.storage.rls.RLSFixtureContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import java.time.OffsetDateTime

/**
 * Composes every DSL scope: auth → study → participant → device → upload → flush → verify → export.
 */
class FullJourneyE2ETest : ChronicleServerTests() {
    @get:Rule val fixtureContext = RLSFixtureContext()
    private val providers = ProvidersBundle.fromSpringContext(testServer.context)
    // Read through the study service to assert the aggregate cache update directly.
    private val studyManager = testServer.context.getBean(StudyManager::class.java)

    @Test
    fun `full participant journey - auth to export`() = chronicleScenario(providers) {
        asUser("test_admin") {
            study(providers.data.study("FullJourney")) {
                val theStudyId = studyId
                val adminClient = client
                var capturedParticipantId = ""

                participant(providers.data.participant()) {
                    capturedParticipantId = participantId
                    val theParticipantId = participantId

                    device(providers.data.androidDevice()) {
                        assertUsageStats(studyManager.getParticipantStats(theStudyId, theParticipantId))
                        val events = providers.data.usageEvents(theStudyId, theParticipantId, count = 25)
                        upload(events) {
                            // Verify upload landed (stats update synchronously in Hazelcast)
                            val stats = studyManager.getParticipantStats(theStudyId, theParticipantId)
                            assertUsageStats(stats, events)

                            // Flush buffer → CHRONICLE_USAGE_EVENTS
                            flush()

                            verify {
                                assertTrue("rowsWritten must be > 0", rowsWritten > 0)
                            }
                        }
                    }

                    // Verify enrollment is reflected in study devices
                    val devices = adminClient.studyApi.getStudyDevices(theStudyId)
                    EnrollmentAssertions.assertParticipantEnrolled(devices, theParticipantId)
                }

                // Export: the synchronous participants-data stream the dashboard downloads.
                val rows = adminClient.testStudyApi.getParticipantsData(
                    theStudyId, ParticipantDataType.UsageEvents, setOf(capturedParticipantId),
                    OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1), "json",
                )
                assertTrue("Expected at least 25 exported rows, got ${rows.size}", rows.size >= 25)
            }
        }
    }
}
