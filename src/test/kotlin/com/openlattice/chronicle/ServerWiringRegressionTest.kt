package com.openlattice.chronicle

import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.storage.PostgresEventColumns
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitFilter
import com.openlattice.chronicle.controllers.AuthTokenController
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.services.studies.StudyLimitsManager
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.studies.tasks.StudyLimitsEnforcementTask
import com.openlattice.chronicle.studies.tasks.StudyLimitsEnforcementTaskDependencies
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyLimits
import com.openlattice.chronicle.util.tests.TestDataFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID
import java.time.OffsetDateTime

class ServerWiringRegressionTest : ChronicleServerTests() {
    @Test
    fun fixturePreservesFlywayPrivilegeRestrictions() {
        RLSRequestContext.clear()
        hds.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT has_table_privilege('chronicle_app', 'audit', 'UPDATE'), " +
                        "has_table_privilege('chronicle_app', 'audit', 'SELECT')",
                ).use { rows ->
                    assertTrue(rows.next())
                    assertFalse(rows.getBoolean(1))
                    assertFalse(rows.getBoolean(2))
                }
            }
        }
    }

    @Test
    fun durationEnforcementWithoutAuthenticationPreservesTerminalStatusesAndAuditsOnce() {
        val studyId = clientAdmin.studyApi.createStudy(Study(title = "duration regression", contact = "test@example.org"))
        // ERASED is a device-key tombstone response; V47 excludes it from persisted enrollment.
        val participants = ParticipationStatus.entries.filter { it != ParticipationStatus.ERASED }.associateWith { status ->
            TestDataFactory.participant().copy(participationStatus = status).also {
                clientAdmin.studyApi.registerParticipant(studyId, it)
            }
        }
        val limits = Mockito.mock(StudyLimitsManager::class.java)
        Mockito.`when`(limits.getStudiesExceedingDurationLimit()).thenReturn(setOf(studyId))
        Mockito.`when`(limits.getStudiesExcceedingDataRetentionPeriod()).thenReturn(emptySet())
        val dependencies = StudyLimitsEnforcementTaskDependencies(sr, limits, testServer.context.getBean(StudyService::class.java))
        val task = object : StudyLimitsEnforcementTask() {
            override fun getDependency() = dependencies
        }
        SecurityContextHolder.clearContext()
        RLSRequestContext.clear()
        task.runTask()
        assertEquals("a stale expiration snapshot cannot pause an open-ended study",
            participants.map { (status, participant) -> participant.participantId to status }.toMap(),
            clientAdmin.studyApi.getStudyParticipants(studyId).associate { it.participantId to it.participationStatus })
        clientAdmin.testStudyLimitsApi.setStudyLimits(studyId, StudyLimits(studyEnds = OffsetDateTime.now().minusMinutes(1)))
        repeat(2) { task.runTask() }
        hds.connection.use { connection ->
            connection.prepareStatement("SELECT participant_id, participation_status FROM study_participants WHERE study_id = ?").use { statement ->
                statement.setObject(1, studyId)
                val actual = statement.executeQuery().use { rows ->
                    buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) }
                }
                assertEquals(participants.map { (status, participant) ->
                    participant.participantId to (if (status == ParticipationStatus.ENROLLED) ParticipationStatus.PAUSED else status).name
                }.toMap(), actual)
            }
            connection.prepareStatement("SELECT count(*) FROM audit WHERE study_id = ? AND ${PostgresEventColumns.AUDIT_EVENT_TYPE.name} = ?").use {
                statement ->
                statement.setString(1, studyId.toString())
                statement.setString(2, AuditEventType.UPDATE_PARTICIPATION_STATUS.name)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(1, rows.getInt(1))
                }
            }
        }
    }

    

    @Test
    fun rootRateLimiterUsesAnnotationsFromTheDispatcherServletChild() {
        val filter = testServer.context.getBean("rateLimitFilter", RateLimitFilter::class.java)
        val login = AuthTokenController::class.java.methods.single { it.name == "dashboardLogin" }
            .getAnnotation(RateLimit::class.java)
        val studyId = UUID.randomUUID()
        fun status(path: String): Int {
            val request = MockHttpServletRequest("POST", path).apply {
                servletPath = "/chronicle"
                pathInfo = path.removePrefix(servletPath)
                remoteAddr = "198.51.100.241"
                contentType = "application/json"
            }
            val response = MockHttpServletResponse()
            filter.doFilter(request, response, MockFilterChain())
            return response.status
        }
        val loginStatuses = (0..login.burstCapacity).map { status("/chronicle/v3/auth/dashboard-login") }
        assertEquals(List(login.burstCapacity.toInt()) { 200 } + 429, loginStatuses)
        listOf(
            "/chronicle/v3/study/$studyId/participants/data",
            "/chronicle/v4/study/$studyId/participant/participant-1/android",
        ).forEach { path ->
            repeat(200) { assertEquals("authenticated route $path is never limited", 200, status(path)) }
        }
    }

    @Test
    fun configuredHazelcastMemberDisablesVendorTelemetry() {
        assertEquals("false", hazelcastInstance.config.getProperty("hazelcast.phone.home.enabled"))
    }
}
