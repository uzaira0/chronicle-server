package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.study.StudyParticipantPolicy
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.organizations.ChronicleDataCollectionSettings
import com.openlattice.chronicle.settings.AppUsageFrequency
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.web.server.ResponseStatusException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

class StudyParticipantPolicyImmutabilityTest {
    private val studyId = UUID.fromString("00000000-0000-0000-0000-000000000765")
    private val priorPolicy = policy("1")
    private val changedPolicy = policy("2")

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
        verify(fixture.activityStatement).setObject(2, studyId)
        verify(fixture.activityStatement).setObject(3, studyId)
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
    fun `legacy AndroidSensor mutation is rejected once a participant or device is active`() {
        val fixture = databaseFixture(hasActiveEnrollment = true)
        val prior = settings(priorPolicy, legacy = AndroidSensorSetting.NO_SENSORS)
        val changed = settings(
            priorPolicy,
            legacy = AndroidSensorSetting(sensors = setOf(AndroidSensorType.accelerometer)),
        )

        val exception = assertThrows(ResponseStatusException::class.java) {
            ensureParticipantPolicyMutable(fixture.connection, studyId, prior, changed)
        }

        org.junit.Assert.assertEquals(409, exception.statusCode.value())
    }

    @Test
    fun `legacy AndroidSensor may be migrated before enrollment`() {
        val fixture = databaseFixture(hasActiveEnrollment = false)
        val prior = settings(priorPolicy, legacy = AndroidSensorSetting.NO_SENSORS)
        val changed = settings(
            priorPolicy,
            legacy = AndroidSensorSetting(sensors = setOf(AndroidSensorType.accelerometer)),
        )

        ensureParticipantPolicyMutable(fixture.connection, studyId, prior, changed)

        verify(fixture.activityStatement).executeQuery()
    }

    @Test
    fun `legacy data collection mutation is rejected once a participant or device is active`() {
        val fixture = databaseFixture(hasActiveEnrollment = true)
        val prior = StudySettings(
            mapOf(
                StudySettingType.ParticipantPolicy to priorPolicy,
                StudySettingType.DataCollection to ChronicleDataCollectionSettings(AppUsageFrequency.DAILY),
            ),
        )
        val changed = StudySettings(
            mapOf(
                StudySettingType.ParticipantPolicy to priorPolicy,
                StudySettingType.DataCollection to ChronicleDataCollectionSettings(AppUsageFrequency.HOURLY),
            ),
        )

        val exception = assertThrows(ResponseStatusException::class.java) {
            ensureParticipantPolicyMutable(fixture.connection, studyId, prior, changed)
        }

        org.junit.Assert.assertEquals(409, exception.statusCode.value())
    }

    @Test
    fun `versioned DataCollection cannot be omitted to reactivate a different legacy policy`() {
        val connection = mock<Connection>()
        val prior = settings(
            priorPolicy,
            collection = AndroidDataCollectionSetting(settingsVersion = 4),
            legacy = AndroidSensorSetting.NO_SENSORS,
        )
        val downgraded = settings(
            priorPolicy,
            legacy = AndroidSensorSetting(sensors = setOf(AndroidSensorType.gyroscope)),
        )

        val exception = assertThrows(ResponseStatusException::class.java) {
            ensureParticipantPolicyMutable(connection, studyId, prior, downgraded)
        }

        org.junit.Assert.assertEquals(409, exception.statusCode.value())
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
        val activityResult = mock<ResultSet>()
        whenever(connection.prepareStatement(any())).thenReturn(lockStatement, activityStatement)
        whenever(lockStatement.executeQuery()).thenReturn(lockResult)
        whenever(lockResult.next()).thenReturn(true)
        whenever(activityStatement.executeQuery()).thenReturn(activityResult)
        whenever(activityResult.next()).thenReturn(true)
        whenever(activityResult.getBoolean(1)).thenReturn(hasActiveEnrollment)
        whenever(activityResult.getBoolean(2)).thenReturn(hasPendingEnrollment)
        return DatabaseFixture(connection, lockStatement, activityStatement)
    }

    private fun settings(
        policy: StudyParticipantPolicy,
        collection: AndroidDataCollectionSetting? = null,
        legacy: AndroidSensorSetting? = null,
    ): StudySettings = StudySettings(
        buildMap {
            put(StudySettingType.ParticipantPolicy, policy)
            collection?.let { put(StudySettingType.DataCollection, it) }
            legacy?.let { put(StudySettingType.AndroidSensor, it) }
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
