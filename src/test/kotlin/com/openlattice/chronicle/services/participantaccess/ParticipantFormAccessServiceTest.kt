package com.openlattice.chronicle.services.participantaccess

import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verifyNoInteractions
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import java.time.OffsetDateTime

class ParticipantFormAccessServiceTest {
    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private val service = ParticipantFormAccessService(storageResolver)

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

    @Test
    fun replayReceiptAcceptsOnlyTheExactBindingBeforeItsDeadline() {
        val now = OffsetDateTime.parse("2026-08-17T12:00:00Z")
        val binding = EnrollmentAttemptBinding(
            attemptId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            studyId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
            participantId = "participant-1",
            sourceDeviceHash = "1".repeat(64),
            deviceId = UUID.fromString("00000000-0000-0000-0000-000000000003"),
            manifestDigest = "2".repeat(64),
            requestHash = "3".repeat(64),
            proposedApiKeyHash = "4".repeat(64),
            enrollmentSettingsVersion = 7,
            enrollmentDisclosureVersion = "policy-7",
            enrollmentEnabledModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
            enrollmentRequiredModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
        )
        val receipt = EnrollmentAttemptReceipt(binding, now.plusHours(24))

        assertTrue(receipt.accepts(binding.copy(), now.plusHours(23)))
        listOf(
            binding.copy(attemptId = UUID.randomUUID()),
            binding.copy(studyId = UUID.randomUUID()),
            binding.copy(participantId = "participant-2"),
            binding.copy(sourceDeviceHash = "5".repeat(64)),
            binding.copy(deviceId = UUID.randomUUID()),
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

    @Test
    fun replacingReviewerCodeLocksIssuanceScopeBeforeRevokingAndInserting() {
        val now = OffsetDateTime.parse("2026-08-17T12:00:00Z")
        val resolver = Mockito.mock(StorageResolver::class.java)
        val dataSource = Mockito.mock(HikariDataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        val lockStatement = Mockito.mock(PreparedStatement::class.java)
        val revokeStatement = Mockito.mock(PreparedStatement::class.java)
        val insertStatement = Mockito.mock(PreparedStatement::class.java)
        `when`(resolver.getPlatformStorage()).thenReturn(dataSource)
        `when`(dataSource.connection).thenReturn(connection)
        `when`(connection.autoCommit).thenReturn(true)
        `when`(connection.prepareStatement(Mockito.anyString())).thenAnswer { invocation ->
            when {
                invocation.getArgument<String>(0).contains("pg_advisory_xact_lock") -> lockStatement
                invocation.getArgument<String>(0).contains("UPDATE participant_form_access_codes") -> revokeStatement
                else -> insertStatement
            }
        }
        `when`(insertStatement.executeUpdate()).thenReturn(1)
        val lockingService = ParticipantFormAccessService(
            resolver,
            Clock.fixed(now.toInstant(), ZoneOffset.UTC),
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
        order.verify(lockStatement).execute()
        order.verify(revokeStatement).executeUpdate()
        order.verify(insertStatement).executeUpdate()
    }
}
