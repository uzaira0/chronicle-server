package com.openlattice.chronicle.services.enrollment

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.CollectionCadence
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.services.participantaccess.EnrollmentAccessCodeScope
import com.openlattice.chronicle.services.participantaccess.EnrollmentAttemptBinding
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.study.EnrollmentManifest
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyParticipantPolicy
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import java.time.OffsetDateTime
import java.sql.Connection
import java.util.LinkedHashMap
import java.util.UUID
import com.openlattice.chronicle.sources.AndroidDevice
import org.springframework.web.server.ResponseStatusException

class EnrollmentManifestServiceTest {
    private val studyService = Mockito.mock(StudyService::class.java)
    private val accessService = Mockito.mock(ParticipantFormAccessService::class.java)
    private val studyId = UUID.fromString("00000000-0000-0000-0000-000000000123")
    private val participantId = "participant-1"
    private val accessCode = "a".repeat(64)
    private val issuedAt = OffsetDateTime.parse("2026-08-17T12:00:00Z")
    private val expiresAt = OffsetDateTime.parse("2026-08-24T12:00:00Z")
    private val policy = StudyParticipantPolicy(
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
        version = "2026-08-17",
        effectiveAt = OffsetDateTime.parse("2026-08-17T00:00:00Z"),
    )
    private val collectionSettings = AndroidDataCollectionSetting(
        modules = linkedMapOf(
            CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = true),
            CollectionModuleId.USAGE_EVENTS to CollectionModuleSetting(enabled = false),
        ),
        settingsVersion = 7,
    )
    private val scope = EnrollmentAccessCodeScope(
        accessCodeId = UUID.fromString("00000000-0000-0000-0000-000000000456"),
        studyId = studyId,
        participantId = participantId,
        issuedAt = issuedAt,
        expiresAt = expiresAt,
    )
    private val attemptId = UUID.fromString("00000000-0000-0000-0000-000000000789").toString()
    private val sourceDeviceId = "android-installation-1"
    private val sourceDevice = AndroidDevice(
        device = "pixel",
        model = "Pixel 9",
        codename = "tokay",
        brand = "google",
        osVersion = "16",
        sdkVersion = "36",
        product = "tokay",
        deviceId = sourceDeviceId,
    )
    private val proposedKey = "ck_01234567_0123456789ABCDEFGHIJKLMNOPQRSTUV" // gitleaks:allow -- deterministic test credential

    @Test
    fun `preview returns authoritative study policy and current collection settings without consuming code`() {
        val service = serviceWithStudy(collectionSettings)

        val preview = service.getPreview(studyId, participantId, accessCode)

        assertEquals("https://research.example.org", preview.manifest.serverOrigin)
        assertEquals(studyId, preview.manifest.studyId)
        assertEquals(participantId, preview.manifest.participantId)
        assertEquals(policy, preview.manifest.participantPolicy)
        assertSame(collectionSettings, preview.manifest.collectionSettings)
        assertEquals(collectionSettings.settingsVersion, preview.manifest.settingsVersion)
        assertEquals(issuedAt, preview.manifest.issuedAt)
        assertEquals(expiresAt, preview.manifest.expiresAt)
        assertTrue(preview.manifestDigest.matches(Regex("^[0-9a-f]{64}$")))
        verify(accessService, never()).consumeEnrollmentAccessCode(accessCode, studyId, participantId)
    }

    @Test
    fun `preview rejects a signed collection policy the Play client cannot enforce`() {
        val unsupportedSettings = AndroidDataCollectionSetting(
            modules = mapOf(
                CollectionModuleId.USAGE_EVENTS to CollectionModuleSetting(
                    enabled = true,
                    collectionCadence = CollectionCadence(intervalSeconds = 60),
                ),
            ),
        )
        val service = serviceWithStudy(unsupportedSettings)

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.getPreview(studyId, participantId, accessCode)
        }

        assertTrue(error.message.orEmpty().contains("usage_events.collectionCadence is not supported"))
        verify(accessService, never()).consumeEnrollmentAccessCode(accessCode, studyId, participantId)
    }

    @Test
    fun `preview fails closed when the study has no participant policy`() {
        val incompleteStudy = Study(
            studyId = studyId,
            title = "Mobility Study",
            description = "Study description",
            contact = "research@example.org",
            settings = StudySettings(
                mapOf(StudySettingType.DataCollection to collectionSettings),
            ),
        )
        Mockito.`when`(accessService.resolveEnrollmentAccessCode(accessCode, studyId, participantId)).thenReturn(scope)
        Mockito.`when`(studyService.getStudy(studyId)).thenReturn(incompleteStudy)
        val service = EnrollmentManifestService(studyService, accessService, "https://research.example.org")

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.getPreview(studyId, participantId, accessCode)
        }

        assertEquals(409, exception.statusCode.value())
    }

    @Test
    fun `public preview rejects legacy-only AndroidSensor settings without a versioned revision`() {
        val legacyStudy = Study(
            studyId = studyId,
            title = "Legacy sensor study",
            contact = "research@example.org",
            settings = StudySettings(
                mapOf(
                    StudySettingType.ParticipantPolicy to policy,
                    StudySettingType.AndroidSensor to AndroidSensorSetting(
                        sensors = setOf(AndroidSensorType.accelerometer),
                    ),
                ),
            ),
        )
        Mockito.`when`(accessService.resolveEnrollmentAccessCode(accessCode, studyId, participantId)).thenReturn(scope)
        Mockito.`when`(studyService.getStudy(studyId)).thenReturn(legacyStudy)
        val service = EnrollmentManifestService(studyService, accessService, "https://research.example.org")

        val exception = assertThrows(ResponseStatusException::class.java) {
            service.getPreview(studyId, participantId, accessCode)
        }

        assertEquals(409, exception.statusCode.value())
        assertTrue(exception.reason.orEmpty().contains("versioned DataCollection"))
    }

    @Test
    fun `canonical digest is stable when module insertion order changes`() {
        val first = manifest(collectionSettings)
        val reversedModules = LinkedHashMap<CollectionModuleId, CollectionModuleSetting>().apply {
            collectionSettings.modules.entries.reversed().forEach { (moduleId, setting) -> put(moduleId, setting) }
        }
        val second = manifest(collectionSettings.copy(modules = reversedModules))

        assertEquals(EnrollmentManifestDigest.compute(first), EnrollmentManifestDigest.compute(second))
    }

    @Test
    fun `canonical digest is stable when health record set insertion order changes`() {
        val firstSettings = AndroidDataCollectionSetting(
            modules = mapOf(
                CollectionModuleId.HEALTH_CONNECT to CollectionModuleSetting(
                    enabled = true,
                    healthConnectRecordTypes = linkedSetOf(
                        HealthConnectRecordType.STEPS,
                        HealthConnectRecordType.DISTANCE,
                    ),
                ),
            ),
        )
        val secondSettings = firstSettings.copy(
            modules = mapOf(
                CollectionModuleId.HEALTH_CONNECT to firstSettings.modules.getValue(CollectionModuleId.HEALTH_CONNECT)
                    .copy(
                        healthConnectRecordTypes = linkedSetOf(
                            HealthConnectRecordType.DISTANCE,
                            HealthConnectRecordType.STEPS,
                        ),
                    ),
            ),
        )

        assertEquals(
            EnrollmentManifestDigest.compute(manifest(firstSettings)),
            EnrollmentManifestDigest.compute(manifest(secondSettings)),
        )
    }

    @Test
    fun `canonical digest is stable for nested sensor sets and omits removed capture policy fields`() {
        val firstSettings = AndroidDataCollectionSetting(
            modules = linkedMapOf(
                CollectionModuleId.SENSOR_ACCELEROMETER to CollectionModuleSetting(
                    enabled = true,
                    sensorPolicy = AndroidSensorSetting(
                        sensors = linkedSetOf(AndroidSensorType.accelerometer, AndroidSensorType.gyroscope),
                    ),
                ),
                CollectionModuleId.AUDIO_CONTENT to CollectionModuleSetting(
                    enabled = true,
                ),
            ),
        )
        val secondSettings = firstSettings.copy(
            modules = linkedMapOf(
                CollectionModuleId.AUDIO_CONTENT to firstSettings.modules.getValue(CollectionModuleId.AUDIO_CONTENT),
                CollectionModuleId.SENSOR_ACCELEROMETER to
                    firstSettings.modules.getValue(CollectionModuleId.SENSOR_ACCELEROMETER).copy(
                        sensorPolicy = AndroidSensorSetting(
                            sensors = linkedSetOf(AndroidSensorType.gyroscope, AndroidSensorType.accelerometer),
                        ),
                    ),
            ),
        )

        assertEquals(
            EnrollmentManifestDigest.compute(manifest(firstSettings)),
            EnrollmentManifestDigest.compute(manifest(secondSettings)),
        )

        val publishedManifest = ObjectMappers.getJsonMapper().writeValueAsString(manifest(firstSettings))
        listOf(
            "audioCapturePolicy",
            "captureWindowSeconds",
            "captureIntervalSeconds",
            "maxDailyCaptureMinutes",
            "gateOnForegroundMedia",
            "excludedAppPackages",
        ).forEach { legacyRawAudioField ->
            assertFalse(publishedManifest.contains(legacyRawAudioField))
        }
    }

    @Test
    fun `configured public origin rejects an out of range port`() {
        listOf(0, 70_000).forEach { port ->
            assertThrows(IllegalArgumentException::class.java) {
                EnrollmentManifestService(studyService, accessService, "https://research.example.org:$port")
            }
        }
    }

    @Test
    fun `final enrollment rejects a stale digest without binding the invitation`() {
        val service = serviceWithStudy(collectionSettings)

        assertFalse(authorize(service, "0".repeat(64)))

        verify(accessService, never()).authorizeEnrollmentAttempt(
            eq(accessCode),
            eq(studyId),
            eq(participantId),
            any(),
            any(),
        )
    }

    @Test
    fun `final enrollment binds code only after current manifest digest matches`() {
        val service = serviceWithStudy(collectionSettings)
        val digest = service.getPreview(studyId, participantId, accessCode).manifestDigest

        assertTrue(authorize(service, digest))

        verify(accessService).authorizeEnrollmentAttempt(
            eq(accessCode),
            eq(studyId),
            eq(participantId),
            any(),
            any(),
        )
    }

    @Test
    fun `final enrollment binds the canonical request and proposed credential hashes`() {
        val service = serviceWithStudy(collectionSettings)
        val digest = service.getPreview(studyId, participantId, accessCode).manifestDigest
        val binding = org.mockito.kotlin.argumentCaptor<EnrollmentAttemptBinding>()
        Mockito.`when`(
            accessService.authorizeEnrollmentAttempt(
                eq(accessCode),
                eq(studyId),
                eq(participantId),
                binding.capture(),
                any(),
            ),
        ).thenAnswer { invocation ->
            val predicate = invocation.getArgument<(Connection, EnrollmentAccessCodeScope) -> Boolean>(4)
            predicate(Mockito.mock(Connection::class.java), scope)
        }

        assertTrue(
            service.authorizeEnrollmentAttempt(
                studyId,
                participantId,
                accessCode,
                digest,
                attemptId,
                sourceDeviceId,
                sourceDevice,
                proposedKey,
            ),
        )

        assertEquals(UUID.fromString(attemptId), binding.firstValue.attemptId)
        assertEquals(studyId, binding.firstValue.studyId)
        assertEquals(participantId, binding.firstValue.participantId)
        assertEquals(digest, binding.firstValue.manifestDigest)
        assertTrue(binding.firstValue.sourceDeviceHash.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(binding.firstValue.requestHash.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(binding.firstValue.proposedApiKeyHash.matches(Regex("^[0-9a-f]{64}$")))
        assertFalse(binding.firstValue.proposedApiKeyHash.contains(proposedKey))
        assertEquals(collectionSettings.settingsVersion, binding.firstValue.enrollmentSettingsVersion)
        assertEquals(policy.version, binding.firstValue.enrollmentDisclosureVersion)
        assertEquals(collectionSettings.effectiveEnabledModuleIds(), binding.firstValue.enrollmentEnabledModules)
        assertEquals(
            collectionSettings.effectiveModules().filterValues { it.enabled && it.required }.keys,
            binding.firstValue.enrollmentRequiredModules,
        )
    }

    @Test
    fun `malformed attempt or proposed credential is rejected before storage`() {
        val service = serviceWithStudy(collectionSettings)
        val sourceDevice = Mockito.mock(com.openlattice.chronicle.sources.SourceDevice::class.java)

        assertFalse(
            service.authorizeEnrollmentAttempt(
                studyId, participantId, accessCode, "b".repeat(64), "not-a-uuid",
                "source", sourceDevice, "ck_short",
            ),
        )
        verify(accessService, never()).authorizeEnrollmentAttempt(
            eq(accessCode), eq(studyId), eq(participantId), any(), any(),
        )
    }

    private fun serviceWithStudy(settings: AndroidDataCollectionSetting): EnrollmentManifestService {
        val currentStudy = study(settings)
        Mockito.`when`(accessService.resolveEnrollmentAccessCode(accessCode, studyId, participantId)).thenReturn(scope)
        Mockito.`when`(
            accessService.resolveEnrollmentAccessCodeForRequest(accessCode, studyId, participantId),
        ).thenReturn(scope)
        Mockito.`when`(studyService.getStudy(studyId)).thenReturn(currentStudy)
        Mockito.`when`(
            accessService.authorizeEnrollmentAttempt(
                eq(accessCode),
                eq(studyId),
                eq(participantId),
                any(),
                any(),
            ),
        ).thenAnswer { invocation ->
            val predicate = invocation.getArgument<(Connection, EnrollmentAccessCodeScope) -> Boolean>(4)
            predicate(Mockito.mock(Connection::class.java), scope)
        }
        return EnrollmentManifestService(
            studyService,
            accessService,
            "https://research.example.org",
        ) { _, _ -> currentStudy }
    }

    private fun authorize(service: EnrollmentManifestService, digest: String): Boolean =
        service.authorizeEnrollmentAttempt(
            studyId,
            participantId,
            accessCode,
            digest,
            attemptId,
            sourceDeviceId,
            sourceDevice,
            proposedKey,
        )

    private fun study(settings: AndroidDataCollectionSetting): Study = Study(
        studyId = studyId,
        title = "Mobility Study",
        description = "Study description",
        contact = "research@example.org",
        settings = StudySettings(
            mapOf(
                StudySettingType.ParticipantPolicy to policy,
                StudySettingType.DataCollection to settings,
            ),
        ),
    )

    private fun manifest(settings: AndroidDataCollectionSetting): EnrollmentManifest = EnrollmentManifest(
        serverOrigin = "https://research.example.org",
        studyId = studyId,
        participantId = participantId,
        studyTitle = "Mobility Study",
        studyDescription = "Study description",
        participantPolicy = policy,
        collectionSettings = settings,
        settingsVersion = settings.settingsVersion,
        issuedAt = issuedAt,
        expiresAt = expiresAt,
    )
}
