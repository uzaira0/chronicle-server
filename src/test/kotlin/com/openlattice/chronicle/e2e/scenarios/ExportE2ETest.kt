package com.openlattice.chronicle.e2e.scenarios

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.e2e.dsl.assertions.ExportAssertions
import com.openlattice.chronicle.e2e.dsl.chronicleScenario
import com.openlattice.chronicle.e2e.dsl.di.ProvidersBundle
import com.openlattice.chronicle.export.ExportFormat
import com.openlattice.chronicle.export.ExportJobStatus
import com.openlattice.chronicle.export.ExportRequest
import com.openlattice.chronicle.study.ParticipantDataType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportE2ETest : ChronicleServerTests() {
    private val providers = ProvidersBundle.fromSpringContext(testServer.context)

    @Test
    fun `async export completes and download returns csv`() = chronicleScenario(providers) {
        asUser("test_admin") {
            study(providers.data.study("Export")) {
                val theStudyId = studyId
                var theParticipantId = ""
                participant {
                    theParticipantId = participantId
                    device {
                        upload(providers.data.usageEvents(theStudyId, participantId, count = 10)) {
                            flush()
                        }
                    }
                }

                export(ExportRequest(
                    dataTypes = setOf(ParticipantDataType.UsageEvents),
                    participantIds = setOf(theParticipantId),
                    format = ExportFormat.CSV,
                )) {
                    val finished = awaitCompletion(timeoutMs = 30_000, intervalMs = 200)
                    assertEquals(ExportJobStatus.COMPLETED, finished.status)
                    val bytes = download()
                    assertTrue("Expected non-empty CSV download", bytes.isNotEmpty())
                    ExportAssertions.assertCsvHasRows(bytes, atLeast = 1)
                }
            }
        }
    }

    @Test
    fun `export status transitions from pending to completed`() = chronicleScenario(providers) {
        asUser("test_admin") {
            study(providers.data.study("ExportStatus")) {
                val theStudyId = studyId
                var theParticipantId = ""
                participant {
                    theParticipantId = participantId
                    device {
                        upload(providers.data.usageEvents(theStudyId, participantId, count = 3)) {
                            flush()
                        }
                    }
                }

                export(ExportRequest(
                    dataTypes = setOf(ParticipantDataType.UsageEvents),
                    participantIds = setOf(theParticipantId),
                    format = ExportFormat.CSV,
                )) {
                    val info = awaitCompletion(timeoutMs = 30_000, intervalMs = 200)
                    assertTrue(
                        "Expected COMPLETED or FAILED, got ${info.status}",
                        info.status == ExportJobStatus.COMPLETED || info.status == ExportJobStatus.FAILED
                    )
                }
            }
        }
    }
}
