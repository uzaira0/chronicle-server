package com.openlattice.chronicle.services.participantaccess

import org.springframework.mock.env.MockEnvironment
import com.openlattice.chronicle.configuration.CookiePolicy
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.ScopedLogCapture
import com.openlattice.chronicle.audit.AuditEntryBuilder
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.configuration.ErrorSanitizationConfig
import com.openlattice.chronicle.controllers.ChronicleServerExceptionHandler
import com.openlattice.chronicle.controllers.ParticipantFormAccessController
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.exceptions.StudyNotFoundException
import com.openlattice.chronicle.services.studies.ParticipantCollectionAcknowledgmentService
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.util.tests.participantFormAccessServiceForTest
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup

class PendingEnrollmentAuditTest {
    private val connection = mock<Connection>()
    private val auditingManager = mock<AuditingManager>()
    private val auditService = mock<AuditService>()
    private val statement = mock<PreparedStatement>()
    private val rows = mock<ResultSet>()
    private val studyId = UUID.randomUUID()
    private val participantId = "participant-private-sentinel-${UUID.randomUUID()}"

    private fun mockMvc() = standaloneSetup(controller()).setControllerAdvice(
        ChronicleServerExceptionHandler(auditingManager, ErrorSanitizationConfig(logFullErrors = false)),
    ).build()

    private fun controller(): ParticipantFormAccessController {
        val storage = mock<StorageResolver>()
        val pool = mock<HikariDataSource>()
        whenever(storage.getPlatformStorage()).thenReturn(pool)
        whenever(pool.connection).thenReturn(connection)
        var autoCommit = true
        whenever(connection.autoCommit).thenAnswer { autoCommit }
        doAnswer { autoCommit = it.getArgument(0); null }.whenever(connection).setAutoCommit(any())
        whenever(connection.prepareStatement(any())).thenReturn(statement)
        whenever(statement.executeQuery()).thenReturn(rows)
        // One pending attempt (access code, device), then the one cancellable attempt it revokes (attempt, key hash).
        whenever(rows.next()).thenReturn(true, false, true, false)
        whenever(rows.getObject(1, UUID::class.java)).thenReturn(UUID.randomUUID())
        whenever(rows.getObject(2, UUID::class.java)).thenReturn(UUID.randomUUID())
        whenever(rows.getString(2)).thenReturn("proposed-key-hash")
        whenever(connection.createStatement()).thenReturn(mock<Statement>())
        whenever(statement.executeUpdate()).thenReturn(1)
        val authorization = mock<AuthorizationManager>()
        whenever(authorization.checkIfHasPermissions(any(), any(), any())).thenReturn(true)
        return ParticipantFormAccessController(
            participantFormAccessService = participantFormAccessServiceForTest(storage, auditingManager = auditingManager),
            enrollmentManager = mock<EnrollmentManager>(),
            studyManager = mock<StudyManager>(),
            participantCollectionAcknowledgmentService = mock<ParticipantCollectionAcknowledgmentService>(),
            auditService = auditService,
            authorizationManager = authorization,
            auditingManager = auditingManager,
            cookiePolicy = CookiePolicy(MockEnvironment()),
        )
    }

    @Test
    fun missingStudyKeepsItsDomainAbsenceAndMapsToHttpNotFound() {
        TestSecurityUtils.setupSecurityContext()
        try {
            val http = mockMvc()
            whenever(rows.next()).thenReturn(false)
            http.perform(get("/v3/study/{studyId}/pending-enrollments", studyId))
                .andExpect(status().isNotFound())
                .andExpect { assertTrue(it.resolvedException is StudyNotFoundException) }
        } finally {
            TestSecurityUtils.clearSecurityContext()
        }
    }

    @Test
    fun cancellationRecordsSuccessOnTheMutationConnectionBeforeCommitting() {
        TestSecurityUtils.setupSecurityContext()
        try {
            controller().cancelPendingEnrollment(studyId, participantId)
            val order = inOrder(auditingManager, connection)
            order.verify(auditingManager).recordEvents(eq(connection), any<List<AuditableEvent>>())
            order.verify(connection).commit()
            verify(auditService, never()).log(any<AuditEntryBuilder>())
        } finally {
            TestSecurityUtils.clearSecurityContext()
        }
    }

    @Test
    fun cancellationAuditAndApplicationLogsExcludeTheParticipantIdentity() {
        TestSecurityUtils.setupSecurityContext()
        try {
            ScopedLogCapture(ChronicleServerExceptionHandler::class.java).use { logs ->
                val http = mockMvc()
                val path = "/v3/study/{studyId}/participant/{participantId}/pending-enrollment/cancel"
                http.perform(post(path, studyId, participantId)).andExpect(status().isNoContent())
                val events = argumentCaptor<List<AuditableEvent>>()
                verify(auditingManager).recordEvents(eq(connection), events.capture())
                val event = events.firstValue.single()
                assertEquals(AuditEventType.REVOKE_PARTICIPANT_ACCESS_CODE, event.eventType)
                assertEquals(studyId, event.study)
                assertEquals(1, event.data["revokedCount"])
                assertFalse(ObjectMappers.newJsonMapper().writeValueAsString(event).contains(participantId))
                doAnswer { throw SQLException(participantId, "08006") }.whenever(auditingManager)
                    .recordEvents(eq(connection), any<List<AuditableEvent>>())
                http.perform(post(path, studyId, participantId)).andExpect(status().isServiceUnavailable())
                assertTrue("Failure must reach a captured application logger", logs.messages.isNotEmpty())
                assertFalse(logs.messages.joinToString("\n").contains(participantId))
            }
        } finally {
            TestSecurityUtils.clearSecurityContext()
        }
    }

    @Test
    fun failedAuditRollsBackCancellationAndPreservesOriginalFailure() {
        val original = SQLException("audit unavailable")
        doAnswer { throw original }.whenever(auditingManager).recordEvents(eq(connection), any<List<AuditableEvent>>())
        doThrow(SQLException("rollback unavailable")).whenever(connection).rollback()
        TestSecurityUtils.setupSecurityContext()
        try {
            val failure = assertThrows(SQLException::class.java) {
                controller().cancelPendingEnrollment(studyId, participantId)
            }
            assertSame(original, failure)
            assertEquals("rollback unavailable", failure.suppressed.single().message)
            verify(connection).rollback()
            verify(connection, never()).commit()
            // setAutoCommit(true) inside a transaction commits it; after a failed rollback the pool rolls back on close.
            verify(connection, never()).autoCommit = true
        } finally {
            TestSecurityUtils.clearSecurityContext()
        }
    }
}
