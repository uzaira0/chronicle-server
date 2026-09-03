package com.openlattice.chronicle.e2e.scenarios

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.e2e.dsl.chronicleScenario
import com.openlattice.chronicle.e2e.dsl.di.ProvidersBundle
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.study.ParticipantDataType
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class DataUploadE2ETest : ChronicleServerTests() {
    private val providers = ProvidersBundle.fromSpringContext(testServer.context)
    // Read through the study service to assert the aggregate cache update directly.
    private val studyManager = testServer.context.getBean(StudyManager::class.java)

    @Test
    fun `upload events appear in participant stats synchronously`() = chronicleScenario(providers) {
        asUser("test_user1") {
            study(providers.data.study("UploadStats")) {
                participant {
                    device {
                        upload(providers.data.usageEvents(studyId, participantId, count = 10)) {
                            assertTrue("Expected rowsWritten > 0", rowsWritten > 0)

                            val stats = studyManager.getParticipantStats(studyId, participantId)
                            assertNotNull("Expected stats entry for participant", stats)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `flush moves events from buffer to storage and they are queryable`() = chronicleScenario(providers) {
        asUser("test_user1") {
            study(providers.data.study("UploadFlush")) {
                participant {
                    val theStudyId = studyId
                    val theParticipantId = participantId
                    device {
                        upload(providers.data.usageEvents(theStudyId, theParticipantId, count = 5)) {
                            flush()

                            val data = client.testStudyApi.getParticipantsData(
                                theStudyId,
                                ParticipantDataType.UsageEvents,
                                setOf(theParticipantId),
                                OffsetDateTime.now().minusDays(1),
                                OffsetDateTime.now().plusDays(1),
                                "json",
                            )
                            assertTrue("Expected data rows after flush, got ${data.size}", data.isNotEmpty())
                        }
                    }
                }
            }
        }
    }
}
