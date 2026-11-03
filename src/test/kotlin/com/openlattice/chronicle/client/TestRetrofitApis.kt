package com.openlattice.chronicle.client

import com.openlattice.chronicle.authorization.AccessCheck
import com.openlattice.chronicle.authorization.AclData
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.Acl
import com.openlattice.chronicle.authorization.Authorization
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.study.ParticipantDataType
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.study.StudyLimits
import com.openlattice.chronicle.timeusediary.TimeUseDiaryResponse
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
