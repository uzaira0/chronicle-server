package com.openlattice.chronicle.client

import com.openlattice.chronicle.authorization.AccessCheck
import com.openlattice.chronicle.authorization.AclData
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.Acl
import com.openlattice.chronicle.authorization.Authorization
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.export.ExportApi
import com.openlattice.chronicle.export.ExportJobInfo
import com.openlattice.chronicle.export.ExportRequest
import com.openlattice.chronicle.notifications.DeliveryType
import com.openlattice.chronicle.notifications.NotificationType
import com.openlattice.chronicle.organizations.ChronicleDataCollectionSettings
import com.openlattice.chronicle.organizations.OrganizationSettings
import com.openlattice.chronicle.study.ParticipantDataType
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.study.StudyLimits
import com.openlattice.chronicle.timeusediary.TimeUseDiaryResponse
import com.openlattice.chronicle.users.ChronicleUserProfile
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Test-only Retrofit interfaces that fix void return types and Kotlin wildcard issues
 * that prevent the production API interfaces from being used as Retrofit clients.
 *
 * Retrofit 2 requires non-void return types and rejects Java wildcard type parameters.
 * Kotlin's covariant collections (List<T>, Set<T>) compile to Java wildcards
 * (List<? extends T>, Set<? extends T>) which Retrofit rejects.
 *
 * Using @JvmSuppressWildcards at function level suppresses wildcards for all type
 * parameters in the function signature.
 */

@JvmSuppressWildcards
interface TestStudyApi {
    // The endpoint defaults to text/csv when no responseType is set; RhizomeJacksonConverterFactory
    // returns a raw String for any text/* content type, which Retrofit can't cast to List.
    // Callers must pass responseType="json" so the server returns application/json.
    @GET("${StudyApi.BASE}${StudyApi.STUDY_ID_PATH}${StudyApi.PARTICIPANTS_PATH}${StudyApi.DATA_PATH}")
    fun getParticipantsData(
        @Path(StudyApi.STUDY_ID) studyId: UUID,
        @Query(StudyApi.DATA_TYPE) dataType: ParticipantDataType,
        @Query(StudyApi.PARTICIPANT_ID) participantIds: Set<String>,
        @Query(StudyApi.START_DATE) startDateTime: OffsetDateTime,
        @Query(StudyApi.END_DATE) endDateTime: OffsetDateTime,
        @Query(StudyApi.RESPONSE_TYPE) responseType: String,
    ): List<Map<String, Any>>
}

interface TestStudyLimitsApi {
    companion object {
        const val BASE = "/chronicle/limits"
    }

    @PUT("$BASE/study/{studyId}")
    fun setStudyLimits(@Path("studyId") studyId: UUID, @Body studyLimits: StudyLimits): Void?
}

interface TestAdminApi {
    companion object {
        const val BASE = "/chronicle/v3/admin"
    }

    @GET("$BASE/reload/cache")
    fun reloadCache(): Void?

    @GET("$BASE/reload/cache/{name}")
    fun reloadCache(@Path("name") name: String): Void?

    @GET("$BASE/event-storage")
    fun moveToEventStorage(): Void?
}

@JvmSuppressWildcards
interface TestExportApi {
    @POST("${StudyApi.BASE}${StudyApi.STUDY_ID_PATH}${ExportApi.EXPORT_PATH}${ExportApi.ASYNC_PATH}")
    fun createAsyncExport(
        @Path(StudyApi.STUDY_ID) studyId: UUID,
        @Body request: ExportRequest,
    ): ExportJobInfo

    @GET("${StudyApi.BASE}${StudyApi.STUDY_ID_PATH}${ExportApi.EXPORT_PATH}${ExportApi.EXPORT_ID_PATH}")
    fun getExportStatus(
        @Path(StudyApi.STUDY_ID) studyId: UUID,
        @Path(ExportApi.EXPORT_ID) exportId: UUID,
    ): ExportJobInfo

    @GET("${StudyApi.BASE}${StudyApi.STUDY_ID_PATH}${ExportApi.EXPORT_PATH}${ExportApi.EXPORT_ID_PATH}${ExportApi.DOWNLOAD_PATH}")
    fun downloadExport(
        @Path(StudyApi.STUDY_ID) studyId: UUID,
        @Path(ExportApi.EXPORT_ID) exportId: UUID,
    ): ResponseBody
}

@JvmSuppressWildcards
interface TestOrganizationApi {
    companion object {
        const val BASE = "/chronicle/v3/organization"
    }

    @PUT("$BASE/{organizationId}/settings")
    fun setOrganizationSettings(
        @Path("organizationId") organizationId: UUID,
        @Body orgSettings: OrganizationSettings,
    ): Void?

    @PUT("$BASE/{organizationId}/data-collection")
    fun setChronicleDataCollectionSettings(
        @Path("organizationId") organizationId: UUID,
        @Body dataCollectionSettings: ChronicleDataCollectionSettings,
    ): Void?

    @PUT("$BASE/{organizationId}/app-component/{appComponent}")
    fun setAppComponentSettings(
        @Path("organizationId") organizationId: UUID,
        @Path("appComponent") appComponent: String,
        @Body settings: Map<String, Any>,
    ): Void?

    @GET("$BASE/{organizationId}/app-component/{appComponent}")
    fun getAppComponentSettings(
        @Path("organizationId") organizationId: UUID,
        @Path("appComponent") appComponent: String,
    ): Map<String, Any>
}

@JvmSuppressWildcards
interface TestNotificationApi {
    companion object {
        const val BASE = "/chronicle/v3/notification"
    }

    @PUT("$BASE/{studyId}/principal/{principalId}/notifications")
    fun setResearcherNotificationSettings(
        @Path("studyId") studyId: UUID,
        @Path("principalId") principalId: String,
        @Body settings: Map<NotificationType, Set<DeliveryType>>,
    ): Void?

    @PUT("$BASE/{studyId}/principal/{principalId}/notifications/{notificationType}")
    fun setResearcherNotificationSettingsByType(
        @Path("studyId") studyId: UUID,
        @Path("principalId") principalId: String,
        @Path("notificationType") notificationType: NotificationType,
        @Body deliveryTypes: Set<DeliveryType>,
    ): Void?
}

@JvmSuppressWildcards
interface TestStudyComplianceApi {
    companion object {
        const val BASE = "/chronicle/compliance"
    }

    @POST("$BASE/notifications")
    fun triggerStudyComplianceNotifications(@Body studyIds: Set<UUID>): Void?
}

@JvmSuppressWildcards
interface TestAuthorizationsApi {
    companion object {
        const val BASE = "/chronicle/v3/authorizations"
    }

    @POST(BASE)
    fun checkAuthorizations(@Body queries: Set<AccessCheck>): List<Authorization>
}

@JvmSuppressWildcards
interface TestPermissionsApi {
    companion object {
        const val BASE = "/chronicle/v3/permissions"
    }

    @PATCH("$BASE/update")
    fun updateAcls(@Body req: List<AclData>): OK

    @POST("$BASE/bulk")
    fun getAcls(@Body aclKeys: Set<AclKey>): Set<Acl>
}

@JvmSuppressWildcards
interface TestTimeUseDiaryApi {
    companion object {
        const val BASE = "/chronicle/v3/time-use-diary"
    }

    @POST("$BASE/{studyId}/participant/{participantId}")
    fun submitTimeUseDiary(
        @Path("studyId") studyId: UUID,
        @Path("participantId") participantId: String,
        @Body responses: List<TimeUseDiaryResponse>,
    ): UUID
}

@JvmSuppressWildcards
interface TestPrincipalApi {
    companion object {
        const val BASE = "/chronicle/principal"
    }

    @POST("$BASE/users")
    fun getUsers(@Body userIds: Set<String>): Map<String, ChronicleUserProfile>
}
