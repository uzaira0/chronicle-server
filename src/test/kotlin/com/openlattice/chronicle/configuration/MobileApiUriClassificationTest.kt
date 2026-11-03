package com.openlattice.chronicle.configuration

import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.controllers.MobileEnrollmentController
import com.openlattice.chronicle.services.participantaccess.EnrollmentAccessCodeScope
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.study.StudySettingType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.RequestMapping
import retrofit2.http.GET
import retrofit2.http.POST
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

class MobileApiUriClassificationTest {
    private val filter = MobileApiSignatureFilter(
        Mockito.mock(HazelcastInstance::class.java, Mockito.RETURNS_DEEP_STUBS),
        MobileApiSignatureFilterTest.SECRET,
        signingRequired = true,
    )

    @Before fun researcherCredential() = TestSecurityUtils.setupSecurityContext()
    @After fun clearCredential() = TestSecurityUtils.clearSecurityContext()

    private fun publishedHmacEndpoints(): List<Pair<String, String>> {
        val study = StudyApi::class.java.getMethod("getStudy", UUID::class.java)
            .getAnnotation(GET::class.java).value
        val upload = StudyApi::class.java.methods.single { it.name == "uploadAndroidUsageEventDataV4" }
            .getAnnotation(POST::class.java).value
        val mobile = MobileEnrollmentController::class.java
        val withdrawal = mobile.getAnnotation(RequestMapping::class.java).path
            .single { it.startsWith(StudyApi.SERVICE + "/") } +
            mobile.methods.single { it.name == "withdrawCurrentEnrollment" }
                .getAnnotation(DeleteMapping::class.java).value.single()
        return listOf("GET" to study, "POST" to upload, "DELETE" to withdrawal).map { (method, path) ->
            method to path.replace("{${StudyApi.STUDY_ID}}", "00000000-0000-0000-0000-000000000001")
                .replace("{${StudyApi.PARTICIPANT_ID}}", "participant")
        }
    }

    private fun uriSpellings(path: String): List<String> {
        val segments = path.trim('/').split('/')
        return (listOf(path, path.replace("study", "st%75dy")) + segments.indices.flatMap { position ->
            listOf(
                segments.mapIndexed { index, segment ->
                    if (index == position) "%%%02X".format(segment.first().code) + segment.drop(1) else segment
                }.joinToString("/", "/"),
                segments.mapIndexed { index, segment ->
                    if (index == position) "$segment;variant=1" else segment
                }.joinToString("/", "/"),
            )
        }).distinct()
    }

    @Test fun everyConfiguredMobileFamilyRequiresSigningAcrossUriSpellings() {
        for ((method, path) in publishedHmacEndpoints()) {
            for (variant in uriSpellings(path)) {
                val request = MockHttpServletRequest(method, variant)
                val response = MockHttpServletResponse()
                val chain = MockFilterChain()
                filter.doFilter(request, response, chain)
                assertEquals("Unsigned mobile endpoint bypass: $method $variant", 401, response.status)
                assertNull(chain.request)
            }
        }
    }

    @Test fun encodedMobilePathRetainsItsRawSigningIdentity() {
        for ((method, rawPath) in publishedHmacEndpoints()) {
            for (encoded in uriSpellings(rawPath).filter { it != rawPath }) {
                assertRawSigningIdentity(method, rawPath, encoded)
            }
        }
    }

    private fun assertRawSigningIdentity(method: String, rawPath: String, encoded: String) {
        for (signedPath in listOf(rawPath, encoded)) {
            val timestamp = Instant.now().epochSecond.toString()
            val nonce = UUID.randomUUID().toString()
            val request = MockHttpServletRequest(method, encoded).apply {
                addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
                addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
                addHeader(MobileApiSignatureFilter.HEADER_SIGNATURE,
                    MobileApiSignatureFilterTest.computeSignature(method, signedPath, timestamp, nonce, byteArrayOf()))
            }
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()
            filter.doFilter(request, response, chain)
            assertEquals(if (signedPath == encoded) 200 else 401, response.status)
            if (signedPath == encoded) assertNotNull(chain.request) else assertNull(chain.request)
        }
    }

    @Test fun encodedPublicSettingsRetainTheirExactMethodBoundary() {
        TestSecurityUtils.clearSecurityContext()
        for (setting in listOf(StudySettingType.DataCollection)) {
            val path = "${StudyApi.BASE};version=1/00000000-0000-0000-0000-000000000001" +
                StudyApi.SETTINGS_PATH + StudyApi.SETTING_TYPE_PATH.replace("{${StudyApi.SETTING_TYPE}}", setting.name)
            for (method in listOf("GET", "POST")) {
                val response = MockHttpServletResponse()
                val chain = MockFilterChain()
                filter.doFilter(MockHttpServletRequest(method, path.replace("study", "st%75dy")), response, chain)
                assertEquals(if (method == "GET") 200 else 401, response.status)
                if (method == "GET") assertNotNull(chain.request) else assertNull(chain.request)
            }
        }
    }

    @Test fun encodedEnrollmentPreservesTheBoundParticipantSegment() {
        val studyId = UUID.randomUUID()
        val participant = "P/+1"
        val code = "a".repeat(64)
        val access = Mockito.mock(ParticipantFormAccessService::class.java)
        val issuedAt = OffsetDateTime.now()
        Mockito.`when`(access.resolveEnrollmentAccessCodeForRequest(code, studyId, participant)).thenReturn(
            EnrollmentAccessCodeScope(UUID.randomUUID(), studyId, participant, issuedAt, issuedAt.plusDays(1)),
        )
        val enrollmentFilter = MobileApiSignatureFilter(
            Mockito.mock(HazelcastInstance::class.java, Mockito.RETURNS_DEEP_STUBS),
            MobileApiSignatureFilterTest.SECRET,
            signingRequired = true,
            participantFormAccessService = access,
        )
        val path = "${StudyApi.V4_BASE};version=1/$studyId${StudyApi.PARTICIPANT_PATH}/P%2F+1${StudyApi.ENROLL_PATH}"
        val request = MockHttpServletRequest("POST", path.replace("study", "st%75dy")).apply {
            addHeader(MobileApiSignatureFilter.HEADER_ENROLLMENT_CODE, code)
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        enrollmentFilter.doFilter(request, response, chain)
        assertEquals(200, response.status)
        assertNotNull(chain.request)
        Mockito.verify(access).resolveEnrollmentAccessCodeForRequest(code, studyId, participant)
    }

    @Test fun namespaceBoundariesDoNotSplitDecodedSeparators() {
        val paths = listOf(
            StudyApi.V4_BASE,
            "${StudyApi.V4_BASE}x/child",
            StudyApi.V4_BASE.replace("/study", "%2Fstudy/child"),
        )
        for (path in paths) {
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()
            filter.doFilter(MockHttpServletRequest("POST", path), response, chain)
            assertEquals(path, 200, response.status)
            assertNotNull(chain.request)
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(MockHttpServletRequest("POST", "${StudyApi.V4_BASE}/%ZZ"), response, chain)
        assertEquals(400, response.status)
        assertNull(chain.request)
    }
}
