package com.openlattice.chronicle.util

import com.geekbeast.rhizome.pods.ConfigurationLoader
import com.geekbeast.postgres.streams.StatementHolder
import com.openlattice.chronicle.authorization.mapstores.UserMapstore
import com.openlattice.chronicle.users.ChronicleUserProfile
import com.openlattice.chronicle.services.upload.SensorDataUploadService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

class LogPrivacyRegressionTest {
    private class Credential { override fun toString() = "credential-sentinel" }

    @Test fun startupLogsOnlyConfigurationIdentity() {
        val loader = object : ConfigurationLoader {
            @Suppress("UNCHECKED_CAST")
            override fun <T> load(clazz: Class<T>): T = Credential() as T
            override fun type() = "test"
        }
        val logs = capture(ConfigurationLoader::class.java) { loader.logAndLoad("Vault", Credential::class.java) }
        assertTrue(logs.contains("Vault"))
        assertFalse(logs.contains("credential-sentinel"))
    }

    @Test fun slowQueryNeverRendersBoundStatements() {
        val statement = mock<PreparedStatement>()
        whenever(statement.toString()).thenReturn("bound-participant-sentinel")
        val logs = capture(StatementHolder::class.java) {
            StatementHolder(mock<Connection>(), statement, mock<ResultSet>(), -1L).close()
        }
        assertTrue(logs.contains("ms"))
        assertFalse(logs.contains("bound-participant-sentinel"))
    }

    @Test fun profileWriteNeverRendersItsBoundInsert() {
        val pool = mock<HikariDataSource>()
        val connection = mock<Connection>()
        val statement = mock<PreparedStatement>()
        whenever(pool.connection).thenReturn(connection)
        whenever(connection.prepareStatement(any())).thenReturn(statement)
        whenever(statement.toString()).thenReturn("private-name private-email profile-sentinel")
        val store = UserMapstore(pool)
        val logs = capture(UserMapstore::class.java) {
            store.store("profile-sentinel", ChronicleUserProfile(id = "profile-sentinel", name = "private-name", email = "private-email"))
        }
        assertFalse(logs.contains("profile-sentinel"))
        assertFalse(logs.contains("private-name"))
        assertFalse(logs.contains("private-email"))
        verify(statement).execute()
    }

    @Test fun uploadInfoOmitsRawStudyAndSubjectIds() {
        val study = UUID.randomUUID()
        val participant = "upload-subject-sentinel"
        val logs = capture(SensorDataUploadService::class.java) {
            runCatching { SensorDataUploadService(mock<StorageResolver>(), mock<StudyService>())
                .upload(study, participant, UUID.randomUUID(), emptyList()) }
        }
        assertTrue(logs.contains("upload buffer"))
        assertFalse(logs.contains(participant))
        assertFalse(logs.contains(study.toString()))
    }

    private fun capture(type: Class<*>, action: () -> Unit): String {
        val messages = mutableListOf<String>()
        val appender = object : AbstractAppender("privacy-${UUID.randomUUID()}", null, null, true, Property.EMPTY_ARRAY) {
            override fun append(event: LogEvent) { messages.add(event.message.formattedMessage) }
        }
        val logger = LogManager.getLogger(type) as Logger
        val previous = logger.level
        appender.start(); logger.addAppender(appender); logger.level = Level.DEBUG
        try { action() } finally { logger.removeAppender(appender); logger.level = previous; appender.stop() }
        return messages.joinToString("\n")
    }

    @Test fun complianceTriggerLogsCountsWithoutViolationsOrRawSubjects() {
        val study = UUID.randomUUID()
        val compliance = mock<com.openlattice.chronicle.study.StudyComplianceManager>()
        whenever(compliance.getNonCompliantStudies(setOf(study))).thenReturn(mapOf(study to mapOf(
            "compliance-subject-sentinel" to listOf(com.openlattice.chronicle.study.ComplianceViolation(
                com.openlattice.chronicle.study.ViolationReason.NO_DATA_UPLOADED, "private-violation-sentinel")))))
        val storage = mock<StorageResolver>()
        val source = mock<HikariDataSource>()
        whenever(storage.getPlatformStorage()).thenReturn(source)
        whenever(source.connection).thenReturn(mock<Connection>())
        val controller = com.openlattice.chronicle.controllers.StudyComplianceController(mock(), mock(), compliance,
            mock(), storage, mock(), mock(), mock())
        com.openlattice.chronicle.controllers.TestSecurityUtils.setupSecurityContext()
        try {
            val logs = capture(com.openlattice.chronicle.controllers.StudyComplianceController::class.java) {
                controller.triggerStudyComplianceNotifications(setOf(study))
            }
            assertTrue(logs.contains("participantCount=1"))
            assertFalse(logs.contains("compliance-subject-sentinel"))
            assertFalse(logs.contains("private-violation-sentinel"))
        } finally { com.openlattice.chronicle.controllers.TestSecurityUtils.clearSecurityContext() }
    }

    @Test fun corsRejectionRedactsParticipantIdsInRequestPaths() {
        val participant = "cors-subject-sentinel"
        val request = org.springframework.mock.web.MockHttpServletRequest("GET",
            "/chronicle/v3/study/${UUID.randomUUID()}/participant/$participant/status")
        request.addHeader("Origin", "null")
        val response = org.springframework.mock.web.MockHttpServletResponse()
        val logs = capture(com.openlattice.chronicle.configuration.CorsValidationFilter::class.java) {
            com.openlattice.chronicle.configuration.CorsValidationFilter(
                com.openlattice.chronicle.configuration.CorsConfiguration(),
            ).doFilter(request, response, mock<jakarta.servlet.FilterChain>())
        }
        assertEquals(403, response.status)
        assertTrue(logs.contains("Blocked cross-origin request"))
        assertFalse(logs.contains(participant))
    }

}
