package com.openlattice.chronicle

import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.storage.PostgresEventColumns
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitConfiguration
import com.openlattice.chronicle.configuration.RateLimitFilter
import com.openlattice.chronicle.controllers.AuthTokenController
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.services.studies.StudyLimitsManager
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.services.delete.DataDeletionMode
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.quality.DataQualityScheduler
import com.openlattice.chronicle.services.quality.DataQualityService
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.studies.tasks.StudyLimitsEnforcementTask
import com.openlattice.chronicle.studies.tasks.StudyLimitsEnforcementTaskDependencies
import com.openlattice.chronicle.study.Study
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
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class ServerLaneRegressionTest : ChronicleServerTests() {
    @Test
    fun fixturePreservesFlywayPrivilegeRestrictions() {
        RLSRequestContext.clear()
        hds.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT has_table_privilege('chronicle_app', 'export_jobs', 'UPDATE'), " +
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
    fun backgroundQualityEvaluationSkipsQuarantineAndStillAlertsForTheOtherParticipant() {
        val studyId = clientAdmin.studyApi.createStudy(Study(title = "quality regression", contact = "test@example.org"))
        val participants = List(2) { TestDataFactory.participant() }
        val studies = testServer.context.getBean(StudyService::class.java)
        RLSRequestContext.clear()
        participants.forEach {
            clientAdmin.studyApi.registerParticipant(studyId, it)
            studies.insertOrUpdateParticipantStats(ParticipantStats(
                studyId, it.participantId, androidUniqueDates = setOf(LocalDate.now(ZoneOffset.UTC)),
            ))
        }
        HazelcastMap.PARTICIPANT_STATS.getMap(hazelcastInstance).flush()
        testServer.context.getBean(DataDeletionOrchestrator::class.java).quarantineParticipant(
            studyId, participants.first().participantId, DataDeletionMode.WITHDRAW_AND_ERASE,
            "server-lane-test", UUID.randomUUID(),
        )
        val enumeration = Mockito.mock(StudyManager::class.java)
        Mockito.`when`(enumeration.getAllStudyIds()).thenReturn(listOf(studyId))
        SecurityContextHolder.clearContext()
        RLSRequestContext.clear()
        DataQualityScheduler(enumeration, testServer.context.getBean(DataQualityService::class.java)).evaluateAllStudies()
        hds.connection.use { connection ->
            connection.prepareStatement("SELECT participant_id FROM data_quality_alerts WHERE study_id = ?").use { statement ->
                statement.setObject(1, studyId)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(participants.last().participantId, rows.getString(1))
                    assertFalse(rows.next())
                }
            }
        }
    }

    @Test
    fun rootRateLimiterUsesAnnotationsFromTheDispatcherServletChild() {
        val filter = testServer.context.getBean("rateLimitFilter", RateLimitFilter::class.java)
        val config = testServer.context.getBean(RateLimitConfiguration::class.java)
        val loginLimit = AuthTokenController::class.java.methods.single { it.name == "dashboardLogin" }
            .getAnnotation(RateLimit::class.java).requestsPerMinute
        val studyId = UUID.randomUUID()
        val paths = mapOf(
            "/chronicle/v3/auth/dashboard-login" to loginLimit,
            "/chronicle/v3/study/$studyId/export/async" to config.sensitiveRequestsPerMinute,
            "/chronicle/v4/study/$studyId/participant/participant-1/android" to config.writeRequestsPerMinute,
        )
        paths.forEach { (path, limit) ->
            val request = MockHttpServletRequest("POST", path).apply {
                servletPath = "/chronicle"
                pathInfo = path.removePrefix(servletPath)
                remoteAddr = "198.51.100.241"
                contentType = "application/json"
            }
            val response = MockHttpServletResponse()
            filter.doFilter(request, response, MockFilterChain())
            assertEquals(path, limit.toString(), response.getHeader(RateLimitFilter.HEADER_LIMIT))
        }
    }

    @Test
    fun configuredHazelcastMemberDisablesVendorTelemetry() {
        assertEquals("false", hazelcastInstance.config.getProperty("hazelcast.phone.home.enabled"))
    }
}
