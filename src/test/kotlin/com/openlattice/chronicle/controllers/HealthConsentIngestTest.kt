package com.openlattice.chronicle.controllers

import com.geekbeast.retrofit.RhizomeRetrofitCallException
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.UsageEnrollmentFixtures
import com.openlattice.chronicle.client.Environment
import com.openlattice.chronicle.client.RetrofitClientFactory
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.AndroidHealthMetricEvent
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionUploadEvidence
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.HealthMetricType
import com.openlattice.chronicle.configuration.MobileApiSignatureFilter.Companion.RANDOM_DEVICE_ID_HEADER
import com.openlattice.chronicle.participantaccess.CreateParticipantFormAccessCodeRequest
import com.openlattice.chronicle.participantaccess.ParticipantFormAccessCodeResponse
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.rls.RLSConnectionCustomizer.withAdminContext
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.util.API_KEY_HEADER
import com.openlattice.chronicle.util.tests.TestDataFactory
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.springframework.http.HttpStatus
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

class HealthConsentIngestTest : ChronicleServerTests() {
    interface HealthApi {
        @POST(StudyApi.V4_BASE + StudyApi.STUDY_ID_PATH + StudyApi.PARTICIPANT_PATH + StudyApi.PARTICIPANT_ID_PATH + "/form-access-codes")
        fun invite(@Path(StudyApi.STUDY_ID) study: UUID, @Path(StudyApi.PARTICIPANT_ID) participant: String,
            @Body request: CreateParticipantFormAccessCodeRequest): ParticipantFormAccessCodeResponse

        @POST(StudyApi.V4_BASE + StudyApi.STUDY_ID_PATH + StudyApi.PARTICIPANT_PATH + StudyApi.PARTICIPANT_ID_PATH +
            StudyApi.ANDROID_PATH + StudyApi.HEALTH_CONNECT_PATH)
        fun upload(@Path(StudyApi.STUDY_ID) study: UUID, @Path(StudyApi.PARTICIPANT_ID) participant: String,
            @Header(RANDOM_DEVICE_ID_HEADER) device: String, @Header(CollectionUploadEvidence.SETTINGS_VERSION_HEADER) version: Int,
            @Body data: List<AndroidHealthMetricEvent>): Int
    }

    @Test fun everyUnconsentedMetricFamilyIsRejectedAtomicallyThroughTheLiveRoute() {
        val api = clientUser1.studyApi
        val study = api.createStudy(Study(title = "Health family consent", contact = "test@example.org"))
        UsageEnrollmentFixtures.prepareUsageFixture(clientUser1, study)
        val current = api.getStudySetting(study, StudySettingType.DataCollection) as AndroidDataCollectionSetting
        api.updateStudySettings(study, StudySettingType.DataCollection, current.copy(modules = current.modules +
            (CollectionModuleId.HEALTH_CONNECT to current.modules.getValue(CollectionModuleId.HEALTH_CONNECT).copy(
                enabled = true, healthConnectRecordTypes = setOf(HealthConnectRecordType.STEPS)))))
        val participant = TestDataFactory.participant()
        api.registerParticipant(study, participant)
        val device = TestDataFactory.androidDevice()
        val researcher = RetrofitClientFactory.newClient(Environment.TESTING_CHRONICLE) { activeFixtureToken(testUser1) }
            .create(HealthApi::class.java)
        val code = researcher.invite(study, participant.participantId,
            CreateParticipantFormAccessCodeRequest(ParticipantFormKind.ENROLLMENT, null)).accessCode
        val enrollment = UsageEnrollmentFixtures.beginUsageEnrollmentFixture(study, participant.participantId, device, code)
        UsageEnrollmentFixtures.acknowledgeUsageFixture(enrollment)
        val settingsVersion = requireNotNull(enrollment.acknowledgment.settingsVersion) {
            "Health fixture enrollment must acknowledge a settings version"
        }
        val http = RetrofitClientFactory.okHttpClient().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header(API_KEY_HEADER, enrollment.replay.proposedApiKey).build())
        }
        val health = RetrofitClientFactory.decorateWithOpenLatticeFactories(
            RetrofitClientFactory.createBaseRhizomeRetrofitBuilder(Environment.TESTING_CHRONICLE, http)).build().create(HealthApi::class.java)
        val now = OffsetDateTime.now()
        fun event(type: HealthMetricType) = AndroidHealthMetricEvent(UUID.randomUUID().toString(), now, "UTC", type,
            1.0, "count", now.toInstant().toEpochMilli(), now.toInstant().toEpochMilli())
        HealthMetricType.entries.filter { it != HealthMetricType.STEPS }.forEach { type ->
            val failure = assertThrows(RhizomeRetrofitCallException::class.java) {
                health.upload(study, participant.participantId, device.randomDeviceId, settingsVersion,
                    listOf(event(HealthMetricType.STEPS), event(type)))
            }
            assertEquals(type.name, HttpStatus.FORBIDDEN.value(), failure.code)
            assertEquals(0, storedRows(study, participant.participantId))
        }
        val accepted = listOf(event(HealthMetricType.STEPS))
        assertEquals(accepted.size, health.upload(study, participant.participantId, device.randomDeviceId, settingsVersion, accepted))
        assertEquals(1, storedRows(study, participant.participantId))
    }

    private fun storedRows(study: UUID, participant: String): Int = hds.connection.use { connection ->
        withAdminContext(connection) {
            connection.createStatement().use { it.execute("SET max_parallel_workers_per_gather=0") }
            connection.prepareStatement("""
                SELECT count(*) FROM ${ChroniclePostgresTables.HEALTH_METRICS.name}
                WHERE study_id = ? AND participant_id = ?
            """.trimIndent()).use {
                it.setObject(1, study); it.setString(2, participant)
                it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
        }
    }
}
