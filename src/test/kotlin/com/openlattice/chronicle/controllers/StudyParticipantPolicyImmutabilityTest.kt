package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.services.studies.ensureParticipantPolicyMutable

import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.services.participantaccess.EnrollmentDisclosureActivity
import com.openlattice.chronicle.study.StudyParticipantPolicy
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.study.StudyWithdrawalSetting
import com.openlattice.chronicle.study.WithdrawalMode
import com.openlattice.chronicle.util.tests.EnrollmentDisclosureActivityResultFixture
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.web.server.ResponseStatusException

class StudyParticipantPolicyImmutabilityTest {
    private val studyId = UUID.fromString("00000000-0000-0000-0000-000000000765")
    private val priorPolicy = policy("1")
    private val changedPolicy = policy("2")

    @Test
    fun withdrawalModeCannotChangeAfterEnrollment() {
        val prior = StudySettings(mapOf(StudySettingType.Withdrawal to StudyWithdrawalSetting(WithdrawalMode.ERASE)))
        val changed = StudySettings(mapOf(StudySettingType.Withdrawal to StudyWithdrawalSetting(
            WithdrawalMode.KEEP_PRIOR_DATA,
        )))
        for (pending in listOf(false, true)) {
            val fixture = databaseFixture(hasActiveEnrollment = !pending, hasPendingEnrollment = pending)
            val exception = assertThrows(ResponseStatusException::class.java) {
                ensureParticipantPolicyMutable(fixture.connection, studyId, prior, changed)
            }
            assertEquals(409, exception.statusCode.value())
        }
        ensureParticipantPolicyMutable(databaseFixture(hasActiveEnrollment = false).connection, studyId, prior, changed)
    }

    @Test
    fun `changed participant policy is rejected when an enrolled participant exists`() {
        val fixture = databaseFixture(hasActiveEnrollment = true)

        assertThrows(ResponseStatusException::class.java) {
            ensureParticipantPolicyMutable(
                fixture.connection,
                studyId,
                settings(priorPolicy),
                settings(changedPolicy),
            )
        }

        verify(fixture.lockStatement).setObject(1, studyId)
        verify(fixture.activityStatement).setObject(1, studyId)
        verify(fixture.activityStatement).setString(2, ParticipationStatus.ENROLLED.name)
        verify(fixture.activityStatement).setObject(3, studyId)
        verify(fixture.activityStatement).setObject(4, studyId)
    }

    @Test
    fun `changed participant policy is allowed before any participant or device is enrolled`() {
        val fixture = databaseFixture(hasActiveEnrollment = false)

        ensureParticipantPolicyMutable(
            fixture.connection,
            studyId,
            settings(priorPolicy),
            settings(changedPolicy),
        )

        verify(fixture.activityStatement).executeQuery()
    }

    @Test
    fun `unchanged participant policy does not touch storage`() {
        val connection = mock<Connection>()

        ensureParticipantPolicyMutable(connection, studyId, settings(priorPolicy), settings(priorPolicy))

        verify(connection, never()).prepareStatement(any())
    }

    @Test
    fun `disclosure settings cannot cross a live replay safe enrollment receipt`() {
        val fixture = databaseFixture(hasActiveEnrollment = false, hasPendingEnrollment = true)
        val prior = settings(priorPolicy, AndroidDataCollectionSetting(settingsVersion = 1))
        val changed = settings(priorPolicy, AndroidDataCollectionSetting(settingsVersion = 2))

        assertThrows(ResponseStatusException::class.java) {
            ensureParticipantPolicyMutable(fixture.connection, studyId, prior, changed)
        }
    }

    @Test
    fun `versioned DataCollection cannot be omitted`() {
        val connection = mock<Connection>()
        val prior = settings(
            priorPolicy,
            collection = AndroidDataCollectionSetting(settingsVersion = 4),
        )
        val downgraded = settings(
            priorPolicy,
        )

        val exception = assertThrows(ResponseStatusException::class.java) {
            ensureParticipantPolicyMutable(connection, studyId, prior, downgraded)
        }

        assertEquals(409, exception.statusCode.value())
        verify(connection, never()).prepareStatement(any())
    }

    private fun databaseFixture(
        hasActiveEnrollment: Boolean,
        hasPendingEnrollment: Boolean = false,
    ): DatabaseFixture {
        val connection = mock<Connection>()
        val lockStatement = mock<PreparedStatement>()
        val lockResult = mock<ResultSet>()
        val activityStatement = mock<PreparedStatement>()
        val activityResult = EnrollmentDisclosureActivityResultFixture.resultSet(EnrollmentDisclosureActivity(
            hasActiveEnrollment = hasActiveEnrollment,
            pendingParticipantIds = if (hasPendingEnrollment) listOf("pending-participant") else emptyList(),
        ))
        whenever(connection.prepareStatement(any())).thenReturn(lockStatement, activityStatement)
        whenever(lockStatement.executeQuery()).thenReturn(lockResult)
        whenever(lockResult.next()).thenReturn(true)
        whenever(activityStatement.executeQuery()).thenReturn(activityResult)
        return DatabaseFixture(connection, lockStatement, activityStatement)
    }

    private fun settings(
        policy: StudyParticipantPolicy,
        collection: AndroidDataCollectionSetting? = null,
    ): StudySettings = StudySettings(
        buildMap {
            put(StudySettingType.ParticipantPolicy, policy)
            collection?.let { put(StudySettingType.DataCollection, it) }
        },
    )

    private fun policy(version: String): StudyParticipantPolicy = StudyParticipantPolicy(
        responsibleInstitution = "Example Research Institute",
        serverOperator = "Example Research Institute",
        researchContact = "research@example.org",
        purpose = "Understand mobility patterns",
        expectedDuration = "30 days",
        procedures = "Run Chronicle in the background",
        foreseeableRisks = "Battery use and privacy risk",
        expectedBenefits = "No direct benefit",
        dataUseAndSharing = "De-identified research analysis",
        retentionAndDeletion = "Delete after five years",
        privacyPolicyUrl = "https://research.example.org/privacy",
        withdrawalUrl = "https://research.example.org/withdraw",
        consentDocumentUrl = "https://research.example.org/consent",
        version = version,
        effectiveAt = OffsetDateTime.parse("2026-08-17T00:00:00Z"),
    )

    private data class DatabaseFixture(
        val connection: Connection,
        val lockStatement: PreparedStatement,
        val activityStatement: PreparedStatement,
    )
}
