package com.openlattice.chronicle.e2e.scenarios

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.e2e.dsl.assertions.EnrollmentAssertions
import com.openlattice.chronicle.e2e.dsl.chronicleScenario
import com.openlattice.chronicle.e2e.dsl.di.ProvidersBundle
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentE2ETest : ChronicleServerTests() {
    private val providers = ProvidersBundle.fromSpringContext(testServer.context)

    @Test
    fun `participant and android device enroll successfully`() = chronicleScenario(providers) {
        asUser("test_user1") {
            study(providers.data.study("Enrollment")) {
                participant {
                    device {
                        val devices = client.studyApi.getStudyDevices(studyId)
                        EnrollmentAssertions.assertParticipantEnrolled(devices, participantId)
                        EnrollmentAssertions.assertDeviceCount(devices, participantId, atLeast = 1)
                    }
                }
            }
        }
    }

    @Test
    fun `participant is known after registration`() = chronicleScenario(providers) {
        asUser("test_user1") {
            study(providers.data.study("KnownParticipant")) {
                participant {
                    val known = client.studyApi.isKnownParticipant(studyId, participantId)
                    assertTrue("Participant $participantId should be known", known)
                }
            }
        }
    }

    @Test
    fun `multiple participants can enroll in same study`() = chronicleScenario(providers) {
        asUser("test_user1") {
            study(providers.data.study("MultiParticipant")) {
                participant {
                    val p1 = participantId
                    // add second participant
                    val p2 = providers.data.participant()
                    client.studyApi.registerParticipant(studyId, p2)
                    ctx.pushCleanup { client.studyApi.deleteStudyParticipants(studyId, setOf(p2.participantId)) }
                    val participants = client.studyApi.getStudyParticipants(studyId).toList()
                    assertTrue(participants.any { it.participantId == p1 })
                    assertTrue(participants.any { it.participantId == p2.participantId })
                }
            }
        }
    }
}
