package com.openlattice.chronicle.client

import com.openlattice.chronicle.EditRevisionApi
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.filters.CreateReceiptFilter
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.study.StudyLimitsApi
import java.util.UUID
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

private val SETTINGS_WRITE = Regex("(?:${Regex.escape(StudyApi.BASE)}/([^/]+)(?:${StudyApi.SETTINGS_PATH}/type/[^/]+)?|" +
    "${Regex.escape(StudyLimitsApi.BASE + StudyLimitsApi.STUDY)}/([^/]+))")

/** Programmatic fixture mutations read current revisions; native HTTP tests exercise missing and stale headers. */
internal fun fixtureMutationHeaders(chain: Interceptor.Chain): Response {
    val request = chain.request()
    val builder = request.newBuilder()
    if (request.method == "POST" && request.header(ParticipantFormAccessFilter.IDEMPOTENCY_HEADER) == null &&
        CreateReceiptFilter.createRoute(request.url.encodedPath.removePrefix(StudyApi.SERVICE).trimEnd('/')) != null) {
        builder.header(ParticipantFormAccessFilter.IDEMPOTENCY_HEADER, UUID.randomUUID().toString())
    }
    if (request.method in setOf("PATCH", "PUT") && request.header(EditRevisionApi.IF_MATCH) == null) {
        SETTINGS_WRITE.matchEntire(request.url.encodedPath)?.let { settings ->
            val study = settings.groupValues.drop(1).first { it.isNotEmpty() }
            read(chain, request, "${StudyApi.BASE}/$study${StudyApi.SETTINGS_PATH}") { snapshot ->
                builder.header(EditRevisionApi.IF_MATCH, checkNotNull(snapshot.header(EditRevisionApi.ETAG)))
            }
        }
    }
    return chain.proceed(builder.build())
}

private fun read(chain: Interceptor.Chain, request: Request, path: String, use: (Response) -> Unit) {
    val url = request.url.newBuilder().encodedPath(path).query(null).build()
    chain.proceed(request.newBuilder().url(url).get().build()).use { snapshot ->
        check(snapshot.isSuccessful) { "Fixture revision read failed with HTTP ${snapshot.code}" }
        use(snapshot)
    }
}

/** A typed status change sent with the participant's status revision as read now, as the dashboard sends it. */
internal fun StudyApi.updateParticipationStatusAsRead(
    studyId: UUID,
    participantId: String,
    status: ParticipationStatus,
): OK {
    val revision = getStudyParticipants(studyId).single { it.participantId == participantId }.statusRevision
    return updateParticipationStatus(studyId, participantId, status, "\"$revision\"")
}
