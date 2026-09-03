package com.openlattice.chronicle.e2e.scenarios

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.e2e.dsl.assertions.EnrollmentAssertions
import com.openlattice.chronicle.e2e.dsl.assertions.ExportAssertions
import com.openlattice.chronicle.e2e.dsl.chronicleScenario
import com.openlattice.chronicle.e2e.dsl.di.ProvidersBundle
import com.openlattice.chronicle.export.ExportFormat
import com.openlattice.chronicle.export.ExportJobStatus
import com.openlattice.chronicle.export.ExportRequest
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.study.ParticipantDataType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Composes every DSL scope: auth → study → participant → device → upload → flush → verify → export → download.
 */
class FullJourneyE2ETest : ChronicleServerTests() {
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
                        upload(providers.data.usageEvents(theStudyId, theParticipantId, count = 25)) {
                            // Verify upload landed (stats update synchronously in Hazelcast)
                            val stats = studyManager.getParticipantStats(theStudyId, theParticipantId)
                            assertNotNull("Stats must exist after upload", stats)

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

                // Async export: admin submits, polls, downloads
                export(ExportRequest(
                    dataTypes = setOf(ParticipantDataType.UsageEvents),
                    participantIds = setOf(capturedParticipantId),
                    format = ExportFormat.CSV,
                )) {
                    val finished = awaitCompletion(timeoutMs = 30_000, intervalMs = 200)
                    assertEquals(
                        "Export must complete successfully",
                        ExportJobStatus.COMPLETED,
                        finished.status
                    )
                    val bytes = download()
                    assertTrue("Downloaded CSV must not be empty", bytes.isNotEmpty())
                    ExportAssertions.assertCsvHasRows(bytes, atLeast = 25)
                }
            }
        }
    }
}
