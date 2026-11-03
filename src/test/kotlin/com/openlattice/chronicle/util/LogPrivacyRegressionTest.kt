package com.openlattice.chronicle.util

import com.geekbeast.rhizome.pods.ConfigurationLoader
import com.geekbeast.postgres.streams.StatementHolder
import com.openlattice.chronicle.ScopedLogCapture
import com.openlattice.chronicle.authorization.mapstores.UserMapstore
import com.openlattice.chronicle.mapstores.stats.ParticipantKey
import com.openlattice.chronicle.mapstores.stats.ParticipantStatsMapstore
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.users.ChronicleUserProfile
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.sql.Connection
import java.sql.BatchUpdateException
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import java.time.OffsetDateTime
import java.sql.Statement
import com.openlattice.chronicle.services.upload.UsageEventQueueEntry
import com.openlattice.chronicle.storage.UsageEventStorageWriter
import org.slf4j.LoggerFactory

class LogPrivacyRegressionTest {
    private class Credential { override fun toString() = "credential-sentinel" }

    @Test fun duplicateTableLogsOnlyKeyedParticipantReferences() {
        val pool = mock<HikariDataSource>()
        val connection = mock<Connection>()
        val prepared = mock<PreparedStatement>()
        val statement = mock<Statement>()
        val permitted = mock<ResultSet>()
        whenever(pool.connection).thenReturn(connection)
        whenever(connection.prepareStatement(any())).thenReturn(prepared)
        whenever(connection.createStatement()).thenReturn(statement)
        whenever(connection.createArrayOf(any(), any())).thenReturn(mock())
        whenever(prepared.executeUpdate()).thenReturn(1)
        whenever(prepared.executeQuery()).thenReturn(permitted)
        whenever(permitted.next()).thenReturn(false)
        whenever(permitted.getInt(1)).thenReturn(1)
        val participant = "duplicate-table-participant-sentinel"
        val entry = UsageEventQueueEntry(UUID.randomUUID(), participant, emptyMap(), OffsetDateTime.now())
        val logs = capture(UsageEventStorageWriter::class.java) {
            assertEquals(1, UsageEventStorageWriter.write(pool, listOf(entry),
                LoggerFactory.getLogger(UsageEventStorageWriter::class.java)) {})
        }
        assertTrue(logs.contains("Creating duplicates table"))
        assertTrue(logs.contains(LogSanitizer.stableFingerprints(setOf(participant), "participant")))
        assertFalse(logs.contains(participant))
    }

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

    @Test fun participantStatsBatchFailureNeverRendersBoundSql() {
        val pool = mock<HikariDataSource>()
        val connection = mock<Connection>()
        val statement = mock<PreparedStatement>()
        val key = ParticipantKey(UUID.randomUUID(), "batch-participant-sentinel")
        val sqlState = "08006"
        val sqlFailure = BatchUpdateException(
            "INSERT INTO participant_stats (participant_id) VALUES ('${key.participantId}')",
            sqlState, intArrayOf(),
        )
        sqlFailure.setNextException(java.sql.SQLException("bound-detail-sentinel", sqlState))
        whenever(pool.connection).thenReturn(connection)
        whenever(connection.prepareStatement(any())).thenReturn(statement)
        whenever(statement.connection).thenReturn(connection)
        whenever(connection.createArrayOf(any(), any())).thenReturn(mock())
        whenever(statement.executeBatch()).thenThrow(sqlFailure)
        val logs = capture(ParticipantStatsMapstore::class.java) {
            assertThrows(IllegalStateException::class.java) {
                ParticipantStatsMapstore(pool).storeAll(mapOf(key to ParticipantStats(key.studyId, key.participantId)))
            }
        }
        assertTrue(logs.contains("category=BatchUpdateException"))
        assertTrue(logs.contains("sqlState=$sqlState"))
        assertFalse(logs.contains(key.participantId))
        assertFalse(logs.contains("bound-detail-sentinel"))
        assertFalse(logs.contains("INSERT INTO"))
        verify(statement).executeBatch()
    }

    private fun capture(type: Class<*>, action: () -> Unit): String = ScopedLogCapture(type).use { capture ->
        val logger = LogManager.getLogger(type) as Logger
        val previous = logger.level
        logger.level = Level.DEBUG
        try { action() } finally { logger.level = previous }
        capture.messages.joinToString("\n")
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
