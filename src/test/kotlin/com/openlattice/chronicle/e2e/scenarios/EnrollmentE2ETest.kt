package com.openlattice.chronicle.e2e.scenarios

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.e2e.dsl.assertions.EnrollmentAssertions
import com.openlattice.chronicle.e2e.dsl.chronicleScenario
import com.openlattice.chronicle.e2e.dsl.di.ProvidersBundle
import com.openlattice.chronicle.study.StudySettingType
import org.junit.Assert.assertEquals
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
    fun `two devices enroll and upload without rewriting the participant policy`() = chronicleScenario(providers) {
        asUser("test_user1") {
            study(providers.data.study("DeviceConsent")) {
                val policy = client.studyApi.getStudySetting(studyId, StudySettingType.ParticipantPolicy)
                participant {
                    repeat(2) {
                        device {
                            upload(providers.data.usageEvents(studyId, participantId, count = 1)) {
                                assertEquals(1, rowsWritten)
                            }
                        }
                    }
                    assertEquals(policy, client.studyApi.getStudySetting(studyId, StudySettingType.ParticipantPolicy))
                    val devices = client.studyApi.getStudyDevices(studyId)
                    EnrollmentAssertions.assertDeviceCount(devices, participantId, atLeast = 2)
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

    @Test
    fun `bulk deletion of eight participants persists the successful audit`() = chronicleScenario(providers) {
        asUser("test_user1") {
            study(providers.data.study("BulkDeletionAudit")) {
                val participants = List(8) { providers.data.participant() }
                participants.forEach { client.studyApi.registerParticipant(studyId, it) }
                val operations = client.studyApi.deleteStudyParticipants(studyId, participants.map { it.participantId }.toSet()).toList()
                assertEquals(participants.size, operations.size)
                val storage = testServer.context.getBean(com.openlattice.chronicle.storage.StorageResolver::class.java)
                storage.getAuditStorage().second.connection.use { connection ->
                    connection.prepareStatement(
                        "SELECT jsonb_array_length(data::jsonb -> 'operationIds') FROM audit WHERE study_id = ? AND audit_event_type = ?",
                    ).use { statement ->
                        statement.setString(1, studyId.toString())
                        statement.setString(2, com.openlattice.chronicle.auditing.AuditEventType.DELETE_PARTICIPANTS.name)
                        statement.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals(operations.size, rows.getInt(1))
                        }
                    }
                }
            }
        }
    }
}
