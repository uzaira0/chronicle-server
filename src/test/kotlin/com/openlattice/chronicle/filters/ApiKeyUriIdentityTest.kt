package com.openlattice.chronicle.filters

import com.openlattice.chronicle.apikey.ApiKeyInfo
import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.util.API_KEY_HEADER
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.time.OffsetDateTime
import java.util.UUID

class ApiKeyUriIdentityTest {
    private val study = UUID.fromString("000000ab-00cd-00ef-0001-00000000abcd")
    private val keys = Mockito.mock(ApiKeyService::class.java)
    private val filter = ApiKeyAuthenticationFilter(keys, Mockito.mock(AuthorizationManager::class.java))
    private val rawKey = "ck_uri_identity"

    @After fun clearAuthentication() = SecurityContextHolder.clearContext()

    @Test fun literalPlusAndEncodedPlusKeepTheSameBoundIdentity() {
        for (spelling in listOf("P+1", "P%2B1", "P%2b1", "%50%2B%31")) {
            assertBound("P+1", "${StudyApi.V4_BASE}/$study${StudyApi.PARTICIPANT_PATH}/$spelling", true)
        }
        for (other in listOf("P%201", "P1", "P%252B1")) {
            assertBound("P+1", "${StudyApi.V4_BASE}/$study${StudyApi.PARTICIPANT_PATH}/$other", false)
        }
    }

    @Test fun nativeStaticSegmentsAndUuidSpellingsReachOnlyTheirBoundStudy() {
        for (base in listOf(StudyApi.BASE, StudyApi.V4_BASE)) {
            val prefixes = listOf(base, base.replace("study", "st%75dy"), "$base;route=1")
            val ids = listOf(study.toString(), study.toString().uppercase(), "ab-cd-ef-1-abcd", "%20$study%20")
            for (prefix in prefixes) for (id in ids) {
                assertBound("P/1", "$prefix/$id/part%69cipant/P%2F1${StudyApi.ANDROID_PATH}", true)
            }
        }
        assertBound("P/1", "${StudyApi.V4_BASE}/${UUID.randomUUID()}/participant/P%2F1", false)
        assertBound("P/1", "${StudyApi.V4_BASE}/not-a-uuid/participant/P%2F1", false)
        assertBound("P/1", "${StudyApi.V4_BASE}/$study/participant/%ZZ", false)
    }

    @Test fun encodedSeparatorsRemainInsideTheirOriginalParticipantSegment() {
        for ((participant, spelling) in listOf("A/B" to "A%2FB", "A" to "A", "A%2FB" to "A%252FB")) {
            for (bound in listOf("A/B", "A", "A%2FB")) {
                assertBound(
                    bound, "${StudyApi.V4_BASE}/$study${StudyApi.PARTICIPANT_PATH}/$spelling", participant == bound,
                )
            }
        }
    }

    @Test fun researcherRouteAllowlistUsesTheSameNativeStudyIdentity() {
        val path = "${StudyApi.BASE};route=1/%20ab-cd-ef-1-abcd%20${StudyApi.PARTICIPANTS_PATH}"
            .replace("study", "st%75dy")
        assertEquals(study, ResearcherApiKeyRoutes.match("GET", path)?.studyId)
        assertNull(ResearcherApiKeyRoutes.match("PUT", path))
        assertNull(ResearcherApiKeyRoutes.match("GET", "${StudyApi.BASE}/$study/participants%2Fstats"))
        assertNull(ResearcherApiKeyRoutes.match("GET", "${StudyApi.BASE}/$study/deletions/not-a-uuid"))
    }

    @Test fun encodedWithdrawalAndErasedStatusKeepTheirMethodBoundaries() {
        Mockito.`when`(keys.isErasedDeviceKey(rawKey)).thenReturn(true)
        val statusPath = "${StudyApi.V4_BASE}/ab-cd-ef-1-abcd/participant/P%2F+1${StudyApi.STATUS_PATH}"
            .replace("study", "st%75dy;route=1")
        assertEquals(200, invoke("GET", statusPath).first.status)
        assertEquals(401, invoke("POST", statusPath).first.status)
        val withdrawal = ApiKeyAuthenticationFilter.CURRENT_ENROLLMENT_PATH.replace("mobile", "mo%62ile;route=1")
        val requestId = UUID.randomUUID()
        assertEquals(200, invoke("DELETE", withdrawal, requestId.toString()).first.status)
        assertEquals(401, invoke("DELETE", withdrawal).first.status)
        assertEquals(401, invoke("POST", withdrawal, requestId.toString()).first.status)
        Mockito.`when`(keys.authenticateWithdrawalApiKey(rawKey, requestId)).thenReturn(key("P/+1"))
        val (response, chain) = invoke("DELETE", withdrawal, requestId.toString())
        assertEquals(200, response.status)
        assertNotNull(chain.request)
    }

    private fun assertBound(participant: String, uri: String, allowed: Boolean) {
        Mockito.`when`(keys.authenticateApiKey(rawKey)).thenReturn(key(participant))
        val (response, chain) = invoke("POST", uri)
        assertEquals(uri, if (allowed) 200 else 403, response.status)
        if (allowed) assertNotNull(chain.request) else assertNull(chain.request)
    }

    private fun key(participant: String): ApiKeyInfo {
        val issued = OffsetDateTime.now()
        return ApiKeyInfo(
            keyId = UUID.randomUUID(), studyId = study, prefix = "uri", name = "uri identity",
            scope = ApiKeyScope.WRITE, createdAt = issued, expiresAt = issued.plusDays(1), participantId = participant,
        )
    }

    private fun invoke(
        method: String,
        uri: String,
        requestId: String? = null,
    ): Pair<MockHttpServletResponse, MockFilterChain> {
        SecurityContextHolder.clearContext()
        val request = MockHttpServletRequest(method, uri).apply {
            addHeader(API_KEY_HEADER, rawKey)
            requestId?.let { addHeader(ApiKeyAuthenticationFilter.WITHDRAWAL_REQUEST_ID_HEADER, it) }
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return response to chain
    }
}
