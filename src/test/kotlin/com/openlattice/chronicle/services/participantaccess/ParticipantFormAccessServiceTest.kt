package com.openlattice.chronicle.services.participantaccess

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.util.tests.participantFormAccessServiceForTest
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.verifyNoInteractions
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ParticipantFormAccessServiceTest {
    @Test
    fun settingLocksDoNotMaterializePendingEnrollmentIds() {
        val source = Mockito.mock(HikariDataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        val statement = Mockito.mock(PreparedStatement::class.java)
        val rows = Mockito.mock(ResultSet::class.java)
        `when`(storageResolver.getPlatformStorage()).thenReturn(source)
        `when`(source.connection).thenReturn(connection)
        `when`(connection.prepareStatement(org.mockito.kotlin.any())).thenReturn(statement)
        `when`(statement.executeQuery()).thenReturn(rows)
        `when`(rows.next()).thenReturn(true)
        `when`(rows.getBoolean(1)).thenReturn(true)
        Mockito.doAnswer { throw AssertionError("A scalar settings-lock query must not load pending enrollment arrays") }
            .`when`(rows).getArray(2)
        org.junit.Assert.assertEquals(com.openlattice.chronicle.study.StudySettingType.entries.filter { it.lockedAfterEnrollment },
            service.getSettingLocks(UUID.randomUUID()))
        Mockito.verify(rows, Mockito.never()).getArray(2)
    }
    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private val service = participantFormAccessServiceForTest(storageResolver)

    @Test
    fun duplicateDeviceIssuanceTargetsAreRejectedBeforeOpeningStorage() {
        val command = ParticipantAccessCodeCommand(
            studyId = UUID.randomUUID(),
            participantId = "participant-1",
            formKind = ParticipantFormKind.APP_USAGE,
            resourceId = null,
            logicalDate = null,
            requestedExpiresAt = null,
            issuerType = ParticipantAccessCodeIssuerType.DEVICE,
            issuedBy = "device:${UUID.randomUUID()}",
        )

        assertThrows(IllegalArgumentException::class.java) {
            service.createAccessCodes(listOf(command, command.copy()))
        }
        verifyNoInteractions(storageResolver)
    }

    /**
     * The daily app-usage survey link is never tied to one day (the server's survey days decide what is answerable),
     * and only questionnaire links name a resource; a malformed request is refused with its reason before storage.
     */
    @Test
    fun malformedAccessCodeRequestsAreRefusedWithTheirReasonBeforeOpeningStorage() {
        val command = ParticipantAccessCodeCommand(
            studyId = UUID.randomUUID(),
            participantId = "participant-1",
            formKind = ParticipantFormKind.APP_USAGE,
            resourceId = null,
            logicalDate = null,
            requestedExpiresAt = null,
            issuerType = ParticipantAccessCodeIssuerType.RESEARCHER,
            issuedBy = "researcher-1",
        )
        mapOf(
            command.copy(logicalDate = java.time.LocalDate.parse("2026-10-31")) to "APP_USAGE access codes are not bound to a day",
            command.copy(resourceId = UUID.randomUUID()) to "resourceId is only valid for QUESTIONNAIRE access codes",
            command.copy(formKind = ParticipantFormKind.ENROLLMENT, resourceId = UUID.randomUUID()) to
                "resourceId is only valid for QUESTIONNAIRE access codes",
            command.copy(formKind = ParticipantFormKind.QUESTIONNAIRE) to "QUESTIONNAIRE access codes require resourceId",
            command.copy(participantId = " ") to "participantId is required",
            command.copy(issuedBy = "") to "issuedBy is required",
        ).forEach { (malformed, reason) ->
            val error = assertThrows(IllegalArgumentException::class.java) { service.createAccessCodes(listOf(malformed)) }
            org.junit.Assert.assertEquals(reason, error.message)
        }
        verifyNoInteractions(storageResolver)
    }

    @Test
    fun replayReceiptAcceptsOnlyTheExactBindingBeforeItsDeadline() {
        val now = OffsetDateTime.parse("2026-08-17T12:00:00Z")
        val binding = EnrollmentAttemptBinding(
            attemptId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            studyId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            participantId = "participant-1",
            sourceDeviceHash = "1".repeat(64),
            randomDeviceId = UUID.fromString("00000000-0000-0000-0000-000000000003"),
            manifestDigest = "2".repeat(64),
            requestHash = "3".repeat(64),
            proposedApiKeyHash = "4".repeat(64),
            enrollmentSettingsVersion = 7,
            enrollmentDisclosureVersion = "policy-7",
            enrollmentEnabledModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
            enrollmentRequiredModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
        )
        val receipt = EnrollmentAttemptReceipt(binding, now.plusHours(24))

        listOf(
            binding.copy(enrollmentSettingsVersion = null),
            binding.copy(enrollmentDisclosureVersion = null),
            binding.copy(enrollmentEnabledModules = null),
            binding.copy(enrollmentRequiredModules = null),
        ).forEach { incomplete ->
            assertFalse(receipt.copy(binding = incomplete).accepts(binding, now))
            assertFalse(receipt.copy(binding = incomplete).accepts(incomplete, now))
        }

        assertTrue(receipt.accepts(binding.copy(), now.plusHours(23)))
        listOf(
            binding.copy(attemptId = UUID.randomUUID()),
            binding.copy(studyId = UUID.randomUUID()),
            binding.copy(participantId = "participant-2"),
            binding.copy(sourceDeviceHash = "5".repeat(64)),
            binding.copy(randomDeviceId = UUID.randomUUID()),
            binding.copy(manifestDigest = "6".repeat(64)),
            binding.copy(requestHash = "7".repeat(64)),
            binding.copy(proposedApiKeyHash = "8".repeat(64)),
            binding.copy(enrollmentSettingsVersion = 8),
            binding.copy(enrollmentDisclosureVersion = "policy-8"),
            binding.copy(enrollmentEnabledModules = setOf(CollectionModuleId.USAGE_EVENTS)),
            binding.copy(enrollmentRequiredModules = emptySet()),
        ).forEach { mismatch -> assertFalse(receipt.accepts(mismatch, now.plusHours(23))) }
        assertFalse(receipt.accepts(binding.copy(), now.plusHours(24)))
    }

    /**
     * Binding an enrollment attempt fails in the database for a reason other than a concurrent duplicate. The caller
     * sees the failure's class and SQLSTATE for retry decisions, never the driver text (which can carry bound values
     * and server detail) or the SQLException cause chain.
     */
    @Test
    fun enrollmentAttemptStorageFailureReachesTheCallerWithoutDriverTextOrCause() {
        val resolver = Mockito.mock(StorageResolver::class.java)
        val dataSource = Mockito.mock(HikariDataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        `when`(resolver.getPlatformStorage()).thenReturn(dataSource)
        `when`(dataSource.connection).thenReturn(connection)
        val autoCommit = AtomicBoolean(true)
        `when`(connection.autoCommit).thenAnswer { autoCommit.get() }
        Mockito.doAnswer { autoCommit.set(it.getArgument(0)); null }.`when`(connection).setAutoCommit(Mockito.anyBoolean())
        val driverText = "permission denied for table enrollment_access_codes; DETAIL: Key (participant_id)=(p-secret)"
        `when`(connection.prepareStatement(Mockito.anyString())).thenThrow(java.sql.SQLException(driverText, "42501"))
        val studyId = UUID.randomUUID()
        val binding = EnrollmentAttemptBinding(
            attemptId = UUID.randomUUID(),
            studyId = studyId,
            participantId = "participant-1",
            sourceDeviceHash = "1".repeat(64),
            randomDeviceId = UUID.randomUUID(),
            manifestDigest = "2".repeat(64),
            requestHash = "3".repeat(64),
            proposedApiKeyHash = "4".repeat(64),
            enrollmentSettingsVersion = 7,
            enrollmentDisclosureVersion = "policy-7",
            enrollmentEnabledModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
            enrollmentRequiredModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
        )

        val sanitized = assertThrows(com.openlattice.chronicle.util.ChronicleStorageException::class.java) {
            participantFormAccessServiceForTest(resolver).authorizeEnrollmentAttempt(
                "a".repeat(64), studyId, "participant-1", binding,
            ) { _, _ -> true }
        }

        org.junit.Assert.assertEquals("42501", sanitized.sqlState)
        org.junit.Assert.assertNull(sanitized.cause)
        assertFalse(sanitized.message.orEmpty().contains("p-secret"))
        assertFalse(sanitized.message.orEmpty().contains("enrollment_access_codes"))
    }

    @Test
    fun replacingReviewerCodeLocksIssuanceScopeBeforeRevokingAndInserting() {
        val now = OffsetDateTime.parse("2026-08-17T12:00:00Z")
        val resolver = Mockito.mock(StorageResolver::class.java)
        val dataSource = Mockito.mock(HikariDataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        val lockStatement = Mockito.mock(PreparedStatement::class.java)
        val revokeStatement = Mockito.mock(PreparedStatement::class.java)
        val insertStatement = Mockito.mock(PreparedStatement::class.java)
        val erasureStatement = Mockito.mock(PreparedStatement::class.java)
        val erasureRows = Mockito.mock(ResultSet::class.java)
        `when`(erasureRows.next()).thenReturn(true)
        `when`(erasureStatement.executeQuery()).thenReturn(erasureRows)
        `when`(resolver.getPlatformStorage()).thenReturn(dataSource)
        `when`(dataSource.connection).thenReturn(connection)
        val autoCommit = AtomicBoolean(true)
        `when`(connection.autoCommit).thenAnswer { autoCommit.get() }
        Mockito.doAnswer { autoCommit.set(it.getArgument(0)); null }.`when`(connection).setAutoCommit(Mockito.anyBoolean())
        `when`(connection.prepareStatement(Mockito.anyString())).thenAnswer { invocation ->
            when {
                invocation.getArgument<String>(0).contains("pg_advisory_xact_lock") -> lockStatement
                invocation.getArgument<String>(0).contains("AS erased") -> erasureStatement
                invocation.getArgument<String>(0).contains("UPDATE participant_form_access_codes") -> revokeStatement
                else -> insertStatement
            }
        }
        `when`(insertStatement.executeUpdate()).thenReturn(1)
        val lockingService = participantFormAccessServiceForTest(
            storageResolver = resolver,
            clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC),
        )

        lockingService.createReplacingAccessCode(
            studyId = UUID.randomUUID(),
            participantId = "play-reviewer",
            formKind = ParticipantFormKind.ENROLLMENT,
            resourceId = null,
            logicalDate = null,
            requestedExpiresAt = now.plusMinutes(15),
            issuerType = ParticipantAccessCodeIssuerType.RESEARCHER,
            issuedBy = "play-reviewer-bootstrap",
        )

        val order = inOrder(lockStatement, revokeStatement, insertStatement)
        order.verify(lockStatement, Mockito.times(2)).execute()
        order.verify(revokeStatement).executeUpdate()
        order.verify(insertStatement).executeUpdate()
    }
}
