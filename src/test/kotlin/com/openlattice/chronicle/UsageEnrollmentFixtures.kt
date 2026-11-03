package com.openlattice.chronicle

import com.openlattice.chronicle.ChronicleServerTests.Companion.hds
import com.openlattice.chronicle.ChronicleServerTests.Companion.testServer
import com.openlattice.chronicle.client.ChronicleClient
import com.openlattice.chronicle.client.Environment
import com.openlattice.chronicle.client.RetrofitClientFactory
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeIssuerType
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.storage.PostgresEventColumns
import com.openlattice.chronicle.storage.rls.RLSConnectionContext
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.study.EnrollmentPreviewResponse
import com.openlattice.chronicle.study.EnrollmentResponse
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudyUpdate
import com.openlattice.chronicle.util.API_KEY_HEADER
import com.openlattice.chronicle.util.tests.TestDataFactory
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** Shared public-protocol enrollment and accepted-upload fixtures. */
object UsageEnrollmentFixtures {
    const val UPLOAD_ROW_CAP = 50_000
    private const val ISSUER = "integration-fixture"
    fun databaseNow(connection: Connection): OffsetDateTime = connection.prepareStatement("SELECT clock_timestamp()").use {
        it.executeQuery().use { rows -> check(rows.next()); rows.getObject(1, OffsetDateTime::class.java) }
    }

    fun enrolledDeviceId(enrollment: UsageEnrollmentFixture): String = hds.connection.use { connection ->
        connection.prepareStatement("SELECT random_device_id FROM api_keys WHERE study_id = ? AND participant_id = ? AND key_prefix = ?").use {
            it.setObject(1, enrollment.replay.studyId)
            it.setString(2, enrollment.replay.participantId)
            it.setString(3, enrollment.replay.proposedApiKey.split('_')[1])
            it.executeQuery().use { rows -> check(rows.next()); rows.getString(1).also { check(!rows.next()) } }
        }
    }

    /** Database receipt stamps keyed by each uploaded row's independent identity. */
    fun storedReceiptTimes(studyId: UUID, participantId: String): Map<String, OffsetDateTime> = hds.connection.use { connection ->
        listOf(Triple("chronicle_usage_events", "client_event_id", "uploaded_at"),
            Triple("battery_telemetry", "sample_id", "uploaded_at"), Triple("upload_diagnostics", "event_id", "uploaded_at"),
            Triple("android_device_sensor_availability", "random_device_id", "reported_at")).flatMap { (table, id, stamp) ->
            connection.prepareStatement("SELECT $id, $stamp FROM $table WHERE study_id::text = ? AND participant_id = ?").use {
                it.setString(1, studyId.toString()); it.setString(2, participantId)
                it.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(rows.getString(1) to rows.getObject(2, OffsetDateTime::class.java))
                } }
            }
        }.toMap()
    }

    /** Includes accepted uploads while the background writer has yet to drain their buffer. */
    fun storedUsageTimestamps(studyId: UUID, participantId: String): Set<Instant> = hds.connection.use { connection ->
        connection.createStatement().use { it.execute("SET max_parallel_workers_per_gather=0") }
        val timestamp = PostgresEventColumns.TIMESTAMP.name
        connection.prepareStatement("""
            SELECT $timestamp FROM chronicle_usage_events WHERE study_id::text = ? AND participant_id = ?
            UNION ALL
            SELECT (sample -> '$timestamp' ->> 'value')::timestamptz
            FROM upload_buffer, jsonb_array_elements(data) sample
            WHERE study_id::text = ? AND participant_id = ? AND upload_type = 'Android'
        """.trimIndent()).use { statement ->
            for (offset in listOf(0, 2)) {
                statement.setString(offset + 1, studyId.toString())
                statement.setString(offset + 2, participantId)
            }
            statement.executeQuery().use { rows ->
                buildSet { while (rows.next()) add(rows.getObject(1, OffsetDateTime::class.java).toInstant()) }
            }
        }
    }

    /** Client event IDs of every accepted usage row, stored or still buffered. */
    fun storedUsageEventIds(studyId: UUID, participantId: String): Set<UUID> = hds.connection.use { connection ->
        connection.createStatement().use { it.execute("SET max_parallel_workers_per_gather=0") }
        val clientEventId = PostgresEventColumns.CLIENT_EVENT_ID.name
        connection.prepareStatement("""
            SELECT $clientEventId FROM chronicle_usage_events WHERE study_id::text = ? AND participant_id = ?
            UNION ALL
            SELECT sample -> '$clientEventId' ->> 'value'
            FROM upload_buffer, jsonb_array_elements(data) sample
            WHERE study_id::text = ? AND participant_id = ? AND upload_type = 'Android'
        """.trimIndent()).use { statement ->
            for (offset in listOf(0, 2)) {
                statement.setString(offset + 1, studyId.toString())
                statement.setString(offset + 2, participantId)
            }
            statement.executeQuery().use { rows -> buildSet { while (rows.next()) add(UUID.fromString(rows.getString(1))) } }
        }
    }

    /** Publish the disclosure and uploaded modules before registering the first participant. */
    fun prepareUsageFixture(client: ChronicleClient, studyId: UUID) {
        val study = client.studyApi.getStudy(studyId)
        client.studyApi.updateStudy(studyId, StudyUpdate(
            modules = study.modules + (StudyFeature.CHRONICLE_DATA_COLLECTION to emptyMap<String, Any>()),
        ))
        val settings = client.studyApi.getStudySetting(studyId, StudySettingType.DataCollection) as AndroidDataCollectionSetting
        val fixtureModules = setOf(CollectionModuleId.USAGE_EVENTS, CollectionModuleId.IN_APP_ACTIVITY_CLASS,
            CollectionModuleId.USER_IDENTIFICATION, CollectionModuleId.UPLOAD_TELEMETRY)
        client.studyApi.updateStudySettings(studyId, StudySettingType.DataCollection, settings.copy(
            modules = settings.modules.mapValues { (module, _) ->
                CollectionDefaults.moduleSetting(module, enabled = module in fixtureModules)
            },
        ))
        client.studyApi.updateStudySettings(studyId, StudySettingType.ParticipantPolicy, TestDataFactory.participantPolicy())
    }

    fun usageSettingsWithoutUserIdentification(
        client: StudyApi,
        studyId: UUID,
    ): AndroidDataCollectionSetting {
        val current = client.getStudySetting(studyId, StudySettingType.DataCollection) as AndroidDataCollectionSetting
        return current.copy(modules = current.modules + (CollectionModuleId.USER_IDENTIFICATION to
            current.modules.getValue(CollectionModuleId.USER_IDENTIFICATION).copy(enabled = false)))
    }

    /** Enroll through the public Android invitation protocol, with consent for the usage fixture. */
    fun enrollUsageFixture(studyId: UUID, participantId: String, device: AndroidDevice): UsageEnrollmentFixture {
        val enrollment = beginUsageEnrollmentFixture(studyId, participantId, device)
        acknowledgeUsageFixture(enrollment)
        return enrollment
    }

    fun beginUsageEnrollmentFixture(
        studyId: UUID,
        participantId: String,
        device: AndroidDevice,
        accessCode: String? = null,
    ): UsageEnrollmentFixture {
        val (replay, preview) = newUsageEnrollment(studyId, participantId, device, accessCode)
        val enrolled = submitUsageEnrollment(replay)
        val mobileHttp = RetrofitClientFactory.okHttpClient().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header(API_KEY_HEADER, requireNotNull(enrolled.apiKey)).build())
        }
        val mobileApi = RetrofitClientFactory.decorateWithOpenLatticeFactories(
            RetrofitClientFactory.createBaseRhizomeRetrofitBuilder(Environment.TESTING_CHRONICLE, mobileHttp),
        ).build().create(StudyApi::class.java)
        return UsageEnrollmentFixture(
            mobileApi = mobileApi,
            acknowledgment = CollectionAcknowledgment(
                acknowledgedModules = preview.manifest.collectionSettings.effectiveEnabledModuleIds(),
                acknowledgedAt = OffsetDateTime.now(),
                settingsVersion = preview.manifest.settingsVersion,
                disclosureVersion = preview.manifest.participantPolicy.version,
                manifestDigest = preview.manifestDigest,
            ),
            replay = replay,
        )
    }

    /** An invitation previewed and a first-enrollment request built, not yet submitted. */
    fun newUsageEnrollment(
        studyId: UUID,
        participantId: String,
        device: AndroidDevice,
        accessCode: String? = null,
    ): Pair<UsageEnrollmentReplay, EnrollmentPreviewResponse> {
        val enrollmentCode = accessCode ?: RLSRequestContext.withContext(
            RLSConnectionContext(ISSUER, setOf(studyId), isAdmin = false),
        ) {
            testServer.context.getBean(ParticipantFormAccessService::class.java).createAccessCode(
                studyId, participantId, ParticipantFormKind.ENROLLMENT, null, null, null,
                ParticipantAccessCodeIssuerType.RESEARCHER, ISSUER,
            ).accessCode
        }
        val preview = publicStudyApi().getEnrollmentPreviewV4(studyId, participantId, enrollmentCode)
        val rawKey = UUID.randomUUID().toString().replace("-", "")
        val proposedKey = "ck_${rawKey.take(ApiKeyService.PREFIX_LENGTH)}_$rawKey"
        val replay = UsageEnrollmentReplay(
            studyId = studyId,
            participantId = participantId,
            device = device,
            enrollmentCode = enrollmentCode,
            manifestDigest = preview.manifestDigest,
            enrollmentAttemptId = UUID.randomUUID().toString(),
            proposedApiKey = proposedKey,
        )
        return replay to preview
    }

    fun acknowledgeUsageFixture(enrollment: UsageEnrollmentFixture) {
        enrollment.mobileApi.reportCollectionAcknowledgmentV4(
            studyId = enrollment.replay.studyId,
            participantId = enrollment.replay.participantId,
            sourceRandomDeviceId = enrollment.replay.device.randomDeviceId,
            acknowledgment = enrollment.acknowledgment,
        )
    }

    fun replayUsageEnrollmentFixture(enrollment: UsageEnrollmentFixture) {
        submitUsageEnrollment(enrollment.replay)
    }

    private fun publicStudyApi(): StudyApi =
        RetrofitClientFactory.newClient(Environment.TESTING_CHRONICLE).create(StudyApi::class.java)

    fun submitUsageEnrollment(replay: UsageEnrollmentReplay): EnrollmentResponse = publicStudyApi().enrollV4(
        studyId = replay.studyId,
        participantId = replay.participantId,
        sourceRandomDeviceId = replay.device.randomDeviceId,
        datasource = replay.device,
        enrollmentCode = replay.enrollmentCode,
        manifestDigest = replay.manifestDigest,
        enrollmentAttemptId = replay.enrollmentAttemptId,
        proposedApiKey = replay.proposedApiKey,
    )

    data class UsageEnrollmentFixture(
        val mobileApi: StudyApi,
        val acknowledgment: CollectionAcknowledgment,
        val replay: UsageEnrollmentReplay,
    )

    data class UsageEnrollmentReplay(
        val studyId: UUID,
        val participantId: String,
        val device: AndroidDevice,
        val enrollmentCode: String,
        val manifestDigest: String,
        val enrollmentAttemptId: String,
        val proposedApiKey: String,
    )
}
