package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionAcknowledgmentEntry
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Appender
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class ParticipantCollectionAcknowledgmentServiceTest {
    private val studyId = UUID.randomUUID()
    private val participantId = "participant-1"
    private val sourceDeviceId = "device-1"
    private val deviceId = com.openlattice.chronicle.util.DeviceIdUtils.deriveDeviceId(
        studyId,
        participantId,
        sourceDeviceId,
    )
    private val accessCodeId = UUID.randomUUID()
    private val apiKeyId = UUID.randomUUID()
    private val digest = "a".repeat(64)
    private val policyVersion = "consent-2026-08-17"

    @Test
    fun `enrollment decision persists only after exact authoritative evidence and module partition`() {
        val fixture = fixture(authority())
        val acknowledgment = enrollmentAcknowledgment()

        val entry = fixture.service.recordAcknowledgment(
            studyId,
            participantId,
            sourceDeviceId,
            apiKeyId,
            acknowledgment,
        )

        verify(fixture.statement).setString(5, "[\"battery_telemetry\"]")
        verify(fixture.statement).setString(10, "[\"usage_events\"]")
        verify(fixture.statement).setString(11, "[\"sensor_accelerometer\"]")
        verify(fixture.statement).setInt(9, 7)
        verify(fixture.statement).setString(13, policyVersion)
        verify(fixture.statement).setString(14, digest)
        verify(fixture.statement).setObject(15, accessCodeId)
        verify(fixture.statement).setObject(16, apiKeyId)
        assertEquals(acknowledgment.unavailableModules, entry.unavailableModules)
        assertEquals(7, entry.settingsVersion)
        assertEquals(policyVersion, entry.disclosureVersion)
        assertEquals(digest, entry.manifestDigest)
    }

    @Test
    fun `recording log fingerprints acknowledgment and study identifiers`() {
        val captured = CopyOnWriteArrayList<String>()
        val appender: Appender = object : AbstractAppender(
            "collection-acknowledgment-id-redaction-capture",
            null,
            null,
            true,
            Property.EMPTY_ARRAY,
        ) {
            override fun append(event: LogEvent) {
                captured.add(event.message.formattedMessage)
            }
        }.also { it.start() }
        val coreLogger = LogManager.getLogger(ParticipantCollectionAcknowledgmentService::class.java) as Logger
        coreLogger.addAppender(appender)

        val entry = try {
            fixture(authority()).service.recordAcknowledgment(
                studyId,
                participantId,
                sourceDeviceId,
                apiKeyId,
                enrollmentAcknowledgment(),
            )
        } finally {
            coreLogger.removeAppender(appender)
            appender.stop()
        }

        val log = captured.joinToString("\n")
        assertTrue("acknowledgment fingerprint missing from log: $log", log.contains("acknowledgment:"))
        assertTrue("study fingerprint missing from log: $log", log.contains("study:"))
        assertFalse("raw acknowledgment ID leaked into log: $log", log.contains(entry.id.toString()))
        assertFalse("raw study ID leaked into log: $log", log.contains(studyId.toString()))
    }

    @Test
    fun `enrollment rejects every client evidence mismatch before insert`() {
        val mismatches = listOf(
            enrollmentAcknowledgment().copy(settingsVersion = 8),
            enrollmentAcknowledgment().copy(disclosureVersion = "other"),
            enrollmentAcknowledgment().copy(manifestDigest = "b".repeat(64)),
            enrollmentAcknowledgment().copy(
                acknowledgedModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
                declinedModules = emptySet(),
                unavailableModules = emptySet(),
            ),
        )

        mismatches.forEach { acknowledgment ->
            val fixture = fixture(authority())
            assertThrows(IllegalArgumentException::class.java) {
                fixture.service.recordAcknowledgment(studyId, participantId, sourceDeviceId, apiKeyId, acknowledgment)
            }
            verify(fixture.statement, never()).executeUpdate()
        }
    }

    @Test
    fun `exact enrollment acknowledgment retry returns the durable receipt without another insert`() {
        val acknowledgment = enrollmentAcknowledgment()
        val existing = CollectionAcknowledgmentEntry(
            id = UUID.randomUUID(),
            studyId = studyId,
            participantId = participantId,
            sourceDeviceId = sourceDeviceId,
            acknowledgedModules = acknowledgment.acknowledgedModules,
            acknowledgedAt = acknowledgment.acknowledgedAt,
            declinedModules = acknowledgment.declinedModules,
            unavailableModules = acknowledgment.unavailableModules,
            trigger = acknowledgment.trigger,
            recordedAt = OffsetDateTime.parse("2026-08-17T12:00:01Z"),
            appVersion = acknowledgment.appVersion,
            settingsVersion = acknowledgment.settingsVersion,
            disclosureVersion = acknowledgment.disclosureVersion,
            manifestDigest = acknowledgment.manifestDigest,
        )
        val fixture = fixture(authority(), existing)

        val replay = fixture.service.recordAcknowledgment(
            studyId, participantId, sourceDeviceId, apiKeyId, acknowledgment,
        )

        assertEquals(existing, replay)
        verify(fixture.statement, never()).executeUpdate()
    }

    @Test
    fun `changed enrollment retry is rejected instead of contradicting the durable receipt`() {
        val acknowledgment = enrollmentAcknowledgment()
        val existing = CollectionAcknowledgmentEntry(
            studyId = studyId,
            participantId = participantId,
            sourceDeviceId = sourceDeviceId,
            acknowledgedModules = acknowledgment.acknowledgedModules,
            acknowledgedAt = acknowledgment.acknowledgedAt,
            declinedModules = acknowledgment.declinedModules,
            unavailableModules = acknowledgment.unavailableModules,
            trigger = acknowledgment.trigger,
            appVersion = acknowledgment.appVersion,
            settingsVersion = acknowledgment.settingsVersion,
            disclosureVersion = acknowledgment.disclosureVersion,
            manifestDigest = acknowledgment.manifestDigest,
        )
        val fixture = fixture(authority(), existing)
        val changed = acknowledgment.copy(
            acknowledgedModules = setOf(CollectionModuleId.USAGE_EVENTS),
            declinedModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
        )

        assertThrows(IllegalArgumentException::class.java) {
            fixture.service.recordAcknowledgment(studyId, participantId, sourceDeviceId, apiKeyId, changed)
        }
        verify(fixture.statement, never()).executeUpdate()
    }

    @Test
    fun `delayed reconsent accepts its server issued historical revision after latest advances`() {
        val historicalAuthority = authority().copy(
            latestSettingsVersion = 9,
            decisionEnabledModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
            decisionRequiredModules = emptySet(),
        )
        val delayed = enrollmentAcknowledgment().copy(
            acknowledgedModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
            declinedModules = emptySet(),
            unavailableModules = emptySet(),
            trigger = ConsentTrigger.SETTINGS_CHANGE,
            settingsVersion = 7,
        )
        val fixture = fixture(historicalAuthority)

        val entry = fixture.service.recordAcknowledgment(
            studyId, participantId, sourceDeviceId, apiKeyId, delayed,
        )

        assertEquals(7, entry.settingsVersion)
    }

    @Test
    fun `later decision cannot cite a revision newer than the latest locked study revision`() {
        val futureAuthority = authority().copy(
            latestSettingsVersion = 9,
            decisionSettingsVersion = 10,
            decisionEnabledModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
            decisionRequiredModules = emptySet(),
        )
        val acknowledgment = enrollmentAcknowledgment().copy(
            acknowledgedModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
            declinedModules = emptySet(),
            unavailableModules = emptySet(),
            trigger = ConsentTrigger.SETTINGS_CHANGE,
            settingsVersion = 10,
        )
        val fixture = fixture(futureAuthority)

        assertThrows(IllegalArgumentException::class.java) {
            fixture.service.recordAcknowledgment(
                studyId, participantId, sourceDeviceId, apiKeyId, acknowledgment,
            )
        }
        verify(fixture.statement, never()).executeUpdate()
    }

    @Test
    fun `later decision must retain the immutable enrollment disclosure`() {
        val acknowledgment = enrollmentAcknowledgment().copy(
            trigger = ConsentTrigger.PARTICIPANT_TOGGLE,
            disclosureVersion = "unissued-disclosure",
        )
        val fixture = fixture(authority())

        assertThrows(IllegalArgumentException::class.java) {
            fixture.service.recordAcknowledgment(
                studyId, participantId, sourceDeviceId, apiKeyId, acknowledgment,
            )
        }
        verify(fixture.statement, never()).executeUpdate()
    }

    @Test
    fun `later decision cannot cite a server revision older than this device enrollment`() {
        val preEnrollmentAuthority = authority().copy(
            latestSettingsVersion = 9,
            decisionSettingsVersion = 6,
            decisionEnabledModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
            decisionRequiredModules = emptySet(),
        )
        val acknowledgment = enrollmentAcknowledgment().copy(
            acknowledgedModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
            declinedModules = emptySet(),
            unavailableModules = emptySet(),
            trigger = ConsentTrigger.SETTINGS_CHANGE,
            settingsVersion = 6,
        )
        val fixture = fixture(preEnrollmentAuthority)

        assertThrows(IllegalArgumentException::class.java) {
            fixture.service.recordAcknowledgment(
                studyId, participantId, sourceDeviceId, apiKeyId, acknowledgment,
            )
        }
        verify(fixture.statement, never()).executeUpdate()
    }

    @Test
    fun `settings change may decline a newly required module as halted consent evidence`() {
        val requiredAuthority = authority().copy(
            latestSettingsVersion = 9,
            decisionSettingsVersion = 9,
            decisionEnabledModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
            decisionRequiredModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
        )
        val declined = enrollmentAcknowledgment().copy(
            acknowledgedModules = emptySet(),
            declinedModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
            unavailableModules = emptySet(),
            trigger = ConsentTrigger.SETTINGS_CHANGE,
            settingsVersion = 9,
        )

        val settingsFixture = fixture(requiredAuthority)
        val halted = settingsFixture.service.recordAcknowledgment(
            studyId, participantId, sourceDeviceId, apiKeyId, declined,
        )
        assertEquals(ConsentTrigger.SETTINGS_CHANGE, halted.trigger)

        val withdrawalFixture = fixture(requiredAuthority)
        val entry = withdrawalFixture.service.recordAcknowledgment(
            studyId,
            participantId,
            sourceDeviceId,
            apiKeyId,
            declined.copy(trigger = ConsentTrigger.WITHDRAWAL),
        )
        assertEquals(ConsentTrigger.WITHDRAWAL, entry.trigger)
    }

    @Test
    fun `participant toggle cannot silently decline a required module without the halt flow`() {
        val requiredAuthority = authority().copy(
            latestSettingsVersion = 9,
            decisionSettingsVersion = 9,
            decisionEnabledModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
            decisionRequiredModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
        )
        val inconsistent = enrollmentAcknowledgment().copy(
            acknowledgedModules = emptySet(),
            declinedModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
            unavailableModules = emptySet(),
            trigger = ConsentTrigger.PARTICIPANT_TOGGLE,
            settingsVersion = 9,
        )
        val fixture = fixture(requiredAuthority)

        assertThrows(IllegalArgumentException::class.java) {
            fixture.service.recordAcknowledgment(
                studyId, participantId, sourceDeviceId, apiKeyId, inconsistent,
            )
        }
        verify(fixture.statement, never()).executeUpdate()
    }

    @Test
    fun `post enrollment unavailable modules cannot hide a required non sensor decision`() {
        val requiredAuthority = authority().copy(
            latestSettingsVersion = 9,
            decisionSettingsVersion = 9,
            decisionRequiredModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
        )
        val fixture = fixture(requiredAuthority)

        assertThrows(IllegalArgumentException::class.java) {
            val bypass = enrollmentAcknowledgment().copy(
                acknowledgedModules = emptySet(),
                declinedModules = setOf(CollectionModuleId.USAGE_EVENTS),
                unavailableModules = setOf(
                    CollectionModuleId.BATTERY_TELEMETRY,
                    CollectionModuleId.SENSOR_ACCELEROMETER,
                ),
                trigger = ConsentTrigger.SETTINGS_CHANGE,
                settingsVersion = 9,
            )
            fixture.service.recordAcknowledgment(
                studyId,
                participantId,
                sourceDeviceId,
                apiKeyId,
                bypass,
            )
        }
        verify(fixture.statement, never()).executeUpdate()
    }

    private fun enrollmentAcknowledgment() = CollectionAcknowledgment(
        acknowledgedModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
        acknowledgedAt = OffsetDateTime.parse("2026-08-17T12:00:00Z"),
        declinedModules = setOf(CollectionModuleId.USAGE_EVENTS),
        unavailableModules = setOf(CollectionModuleId.SENSOR_ACCELEROMETER),
        trigger = ConsentTrigger.ENROLLMENT,
        appVersion = "1.0.0",
        settingsVersion = 7,
        disclosureVersion = policyVersion,
        manifestDigest = digest,
    )

    private fun authority() = CollectionAcknowledgmentAuthority(
        accessCodeId = accessCodeId,
        apiKeyId = apiKeyId,
        enrollmentManifestDigest = digest,
        enrollmentSettingsVersion = 7,
        enrollmentDisclosureVersion = policyVersion,
        enrollmentEnabledModules = setOf(
            CollectionModuleId.BATTERY_TELEMETRY,
            CollectionModuleId.USAGE_EVENTS,
            CollectionModuleId.SENSOR_ACCELEROMETER,
        ),
        enrollmentRequiredModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
        latestSettingsVersion = 7,
        immutableDisclosureVersion = policyVersion,
        decisionSettingsVersion = 7,
        decisionEnabledModules = setOf(
            CollectionModuleId.BATTERY_TELEMETRY,
            CollectionModuleId.USAGE_EVENTS,
            CollectionModuleId.SENSOR_ACCELEROMETER,
        ),
        decisionRequiredModules = setOf(CollectionModuleId.BATTERY_TELEMETRY),
    )

    private fun fixture(
        authority: CollectionAcknowledgmentAuthority,
        existingEnrollment: CollectionAcknowledgmentEntry? = null,
    ): Fixture {
        val storageResolver = Mockito.mock(StorageResolver::class.java)
        val dataSource = Mockito.mock(HikariDataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        val statement = Mockito.mock(PreparedStatement::class.java)
        val contextStatement = Mockito.mock(Statement::class.java)
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(dataSource)
        Mockito.`when`(dataSource.connection).thenReturn(connection)
        Mockito.`when`(connection.autoCommit).thenReturn(true)
        Mockito.`when`(connection.createStatement()).thenReturn(contextStatement)
        Mockito.`when`(connection.prepareStatement(Mockito.anyString())).thenReturn(statement)
        Mockito.`when`(statement.executeUpdate()).thenReturn(1)
        val service = ParticipantCollectionAcknowledgmentService(
            storageResolver = storageResolver,
            authorityLoader = {
                    _, loadedStudy, loadedParticipant, loadedDevice, loadedApiKey, loadedSettingsVersion ->
                require(loadedStudy == studyId)
                require(loadedParticipant == participantId)
                require(loadedDevice == deviceId)
                require(loadedApiKey == apiKeyId)
                require(
                    loadedSettingsVersion == authority.decisionSettingsVersion ||
                        loadedSettingsVersion == authority.enrollmentSettingsVersion,
                )
                authority
            },
            enrollmentReplayLoader = { _, loadedAccessCodeId, loadedApiKeyId ->
                require(loadedAccessCodeId == accessCodeId)
                require(loadedApiKeyId == apiKeyId)
                existingEnrollment
            },
        )
        return Fixture(service, statement)
    }

    private data class Fixture(
        val service: ParticipantCollectionAcknowledgmentService,
        val statement: PreparedStatement,
    )
}
