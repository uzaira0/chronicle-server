package com.openlattice.chronicle.configuration

import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.audit.AuditLogEntry
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.filters.MobileApiHmacAuthenticationToken
import com.openlattice.chronicle.filters.MobileEnrollmentAuthenticationToken
import com.openlattice.chronicle.filters.MobileReviewerAuthenticationToken
import com.openlattice.chronicle.services.participantaccess.EnrollmentAccessCodeScope
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import org.junit.AfterClass
import org.junit.After
import org.junit.Assert
import org.junit.BeforeClass
import org.junit.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.util.*
import jakarta.servlet.http.Cookie
import java.util.zip.DeflaterOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import jakarta.servlet.http.HttpServletRequest
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.argumentCaptor

/**
 * Unit test for the HMAC signature filter (Item 4/9).
 *
 * Tests:
 * - Valid signature passes through
 * - Invalid signature is rejected with 401
 * - Replay (duplicate nonce) is rejected with 401
 * - Non-mobile paths bypass the filter
 */
class MobileApiSignatureFilterTest {

    companion object {
        private const val SECRET = "test-secret-key-for-hmac-at-least-32-bytes!!"
        private const val MOBILE_DATA_PATH =
            "/chronicle/v3/study/00000000-0000-0000-0000-000000000001/participant/test-participant/android/data"
        private const val MOBILE_STATUS_PATH =
            "/chronicle/v3/study/00000000-0000-0000-0000-000000000001/participant/test-participant/android/status"
        private const val MOBILE_UPLOAD_PATH =
            "/chronicle/v4/study/00000000-0000-0000-0000-000000000001/participant/test-participant/android/upload"
        private const val MOBILE_WITHDRAWAL_PATH = "/chronicle/v4/mobile/enrollments/current"
        private const val REVIEWER_ENROLLMENT_PATH = "/chronicle/v4/mobile/reviewer-enrollment"
        private const val MOBILE_ENROLLMENT_PATH =
            "/chronicle/v4/study/00000000-0000-0000-0000-000000000001/participant/test-participant/enroll"
        private const val MOBILE_ENROLLMENT_PREVIEW_PATH =
            "/chronicle/v4/study/00000000-0000-0000-0000-000000000001/participant/test-participant/enrollment-preview"
        private const val LEGACY_MOBILE_ENROLLMENT_PATH =
            "/chronicle/v3/study/00000000-0000-0000-0000-000000000001/participant/test-participant/device-1/enroll"
        private const val PUBLIC_DATA_COLLECTION_SETTINGS_PATH =
            "/chronicle/v3/study/00000000-0000-0000-0000-000000000001/settings/type/DataCollection"
        private const val WEB_ACCESS_CODE_PATH =
            "/chronicle/v3/study/00000000-0000-0000-0000-000000000001/participant/test-participant/form-access-codes"
        private lateinit var hz: HazelcastInstance

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val config = Config()
            config.clusterName = "mobile-filter-test-${UUID.randomUUID()}"
            config.networkConfig.join.multicastConfig.isEnabled = false
            hz = Hazelcast.newHazelcastInstance(config)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            hz.shutdown()
        }

        private fun computeSignature(
            method: String,
            path: String,
            timestamp: String,
            nonce: String,
            body: ByteArray,
            secret: String = SECRET,
        ): String {
            val bodyHash = MessageDigest.getInstance("SHA-256")
                .digest(body)
                .joinToString("") { "%02x".format(it) }

            val signingString = "${method.uppercase()}|$path|$timestamp|$nonce|$bodyHash"

            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return Base64.getEncoder().encodeToString(
                mac.doFinal(signingString.toByteArray(Charsets.UTF_8))
            )
        }

        private fun deflate(body: ByteArray): ByteArray {
            val output = ByteArrayOutputStream()
            DeflaterOutputStream(output).use { it.write(body) }
            return output.toByteArray()
        }
    }

    private fun newFilter(
        signingRequired: Boolean = true,
        internalWebSecret: String = "",
        participantFormAccessService: ParticipantFormAccessService? = null,
        reviewerEnrollmentSecret: String = "",
        reviewerStudyId: UUID? = null,
        auditService: AuditService? = null,
    ): MobileApiSignatureFilter {
        return MobileApiSignatureFilter(
            hazelcastInstance = hz,
            signingSecret = SECRET,
            signingRequired = signingRequired,
            internalWebSecret = internalWebSecret,
            participantFormAccessService = participantFormAccessService,
            reviewerEnrollmentSecret = reviewerEnrollmentSecret,
            reviewerStudyId = reviewerStudyId,
            auditService = auditService,
        )
    }

    @After
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `valid enrollment signature creates study scoped bootstrap authentication`() {
        val filter = newFilter()
        val body = """{"device":"test"}""".toByteArray()
        val timestamp = Instant.now().epochSecond.toString()
        val nonce = UUID.randomUUID().toString()
        val request = MockHttpServletRequest("POST", MOBILE_ENROLLMENT_PATH).apply {
            setContent(body)
            addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
            addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
            addHeader(
                MobileApiSignatureFilter.HEADER_SIGNATURE,
                computeSignature("POST", MOBILE_ENROLLMENT_PATH, timestamp, nonce, body),
            )
        }

        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)

        val authentication = SecurityContextHolder.getContext().authentication
        Assert.assertTrue(authentication is MobileApiHmacAuthenticationToken)
        Assert.assertEquals(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            (authentication as MobileApiHmacAuthenticationToken).studyId,
        )
        Assert.assertNotNull(chain.request)
        Assert.assertEquals(200, response.status)
    }

    @Test
    fun `v4 enrollment code authenticates without consuming before digest verification`() {
        val accessService = mock(ParticipantFormAccessService::class.java)
        val enrollmentCode = "a".repeat(64)
        val studyId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        `when`(accessService.resolveEnrollmentAccessCodeForRequest(enrollmentCode, studyId, "test-participant"))
            .thenAnswer {
                val authentication = SecurityContextHolder.getContext().authentication
                Assert.assertTrue(authentication is MobileEnrollmentAuthenticationToken)
                Assert.assertEquals(studyId, (authentication as MobileEnrollmentAuthenticationToken).studyId)
                EnrollmentAccessCodeScope(
                    UUID.randomUUID(),
                    studyId,
                    "test-participant",
                    OffsetDateTime.parse("2026-08-17T00:00:00Z"),
                    OffsetDateTime.parse("2026-08-24T00:00:00Z"),
                )
            }
        val filter = newFilter(participantFormAccessService = accessService)
        val request = MockHttpServletRequest("POST", MOBILE_ENROLLMENT_PATH).apply {
            setContent("""{"device":"test"}""".toByteArray())
            addHeader(MobileApiSignatureFilter.HEADER_ENROLLMENT_CODE, enrollmentCode)
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        verify(accessService).resolveEnrollmentAccessCodeForRequest(enrollmentCode, studyId, "test-participant")
        Mockito.verify(accessService, Mockito.never())
            .consumeEnrollmentAccessCode(enrollmentCode, studyId, "test-participant")
        val authentication = SecurityContextHolder.getContext().authentication
        Assert.assertTrue(authentication is MobileEnrollmentAuthenticationToken)
        Assert.assertEquals(studyId, (authentication as MobileEnrollmentAuthenticationToken).studyId)
        Assert.assertNotNull(chain.request)
        Assert.assertEquals(200, response.status)
    }

    @Test
    fun `invalid enrollment code is rejected without entering the controller chain`() {
        val accessService = mock(ParticipantFormAccessService::class.java)
        val enrollmentCode = "b".repeat(64)
        val studyId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        `when`(accessService.resolveEnrollmentAccessCodeForRequest(enrollmentCode, studyId, "test-participant"))
            .thenAnswer {
                val authentication = SecurityContextHolder.getContext().authentication
                Assert.assertTrue(authentication is MobileEnrollmentAuthenticationToken)
                Assert.assertEquals(studyId, (authentication as MobileEnrollmentAuthenticationToken).studyId)
                null
            }
        val filter = newFilter(participantFormAccessService = accessService)
        val request = MockHttpServletRequest("POST", MOBILE_ENROLLMENT_PATH).apply {
            addHeader(MobileApiSignatureFilter.HEADER_ENROLLMENT_CODE, enrollmentCode)
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertEquals(401, response.status)
        Assert.assertNull(chain.request)
        Assert.assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `preview validates enrollment code without consuming it`() {
        val accessService = mock(ParticipantFormAccessService::class.java)
        val enrollmentCode = "c".repeat(64)
        val studyId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        `when`(accessService.resolveEnrollmentAccessCode(enrollmentCode, studyId, "test-participant"))
            .thenReturn(
                EnrollmentAccessCodeScope(
                    UUID.randomUUID(),
                    studyId,
                    "test-participant",
                    OffsetDateTime.parse("2026-08-17T00:00:00Z"),
                    OffsetDateTime.parse("2026-08-24T00:00:00Z"),
                ),
            )
        val filter = newFilter(participantFormAccessService = accessService)
        val request = MockHttpServletRequest("GET", MOBILE_ENROLLMENT_PREVIEW_PATH).apply {
            addHeader(MobileApiSignatureFilter.HEADER_ENROLLMENT_CODE, enrollmentCode)
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        verify(accessService).resolveEnrollmentAccessCode(enrollmentCode, studyId, "test-participant")
        Mockito.verify(accessService, Mockito.never())
            .consumeEnrollmentAccessCode(enrollmentCode, studyId, "test-participant")
        Assert.assertNotNull(chain.request)
        Assert.assertEquals(200, response.status)
    }

    @Test
    fun `legacy v3 enrollment code remains one time at the filter boundary`() {
        val accessService = mock(ParticipantFormAccessService::class.java)
        val enrollmentCode = "d".repeat(64)
        val studyId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        `when`(accessService.consumeEnrollmentAccessCode(enrollmentCode, studyId, "test-participant"))
            .thenReturn(true)
        val filter = newFilter(participantFormAccessService = accessService)
        val request = MockHttpServletRequest("POST", LEGACY_MOBILE_ENROLLMENT_PATH).apply {
            addHeader(MobileApiSignatureFilter.HEADER_ENROLLMENT_CODE, enrollmentCode)
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        verify(accessService).consumeEnrollmentAccessCode(enrollmentCode, studyId, "test-participant")
        Assert.assertNotNull(chain.request)
        Assert.assertEquals(200, response.status)
    }

    @Test
    fun `wrong method never consumes a legacy enrollment code`() {
        val accessService = mock(ParticipantFormAccessService::class.java)
        val request = MockHttpServletRequest("GET", LEGACY_MOBILE_ENROLLMENT_PATH).apply {
            addHeader(MobileApiSignatureFilter.HEADER_ENROLLMENT_CODE, "e".repeat(64))
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        newFilter(participantFormAccessService = accessService).doFilter(request, response, chain)

        Mockito.verifyNoInteractions(accessService)
        Assert.assertNull(chain.request)
        Assert.assertEquals(401, response.status)
    }

    @Test
    fun `reviewer secret authenticates only the exact bootstrap POST with configured study scope`() {
        val studyId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val reviewerSecret = "reviewer-console-secret-with-at-least-32-random-chars"
        val filter = newFilter(
            reviewerEnrollmentSecret = reviewerSecret,
            reviewerStudyId = studyId,
        )
        val request = MockHttpServletRequest("POST", REVIEWER_ENROLLMENT_PATH).apply {
            addHeader(MobileApiSignatureFilter.HEADER_REVIEWER_SECRET, reviewerSecret)
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        val authentication = SecurityContextHolder.getContext().authentication
        Assert.assertTrue(authentication is MobileReviewerAuthenticationToken)
        Assert.assertEquals(studyId, (authentication as MobileReviewerAuthenticationToken).studyId)
        Assert.assertNotNull(chain.request)
        Assert.assertEquals(200, response.status)
    }

    @Test
    fun `reviewer bootstrap is disabled as not found when no operator secret is configured`() {
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        newFilter().doFilter(
            MockHttpServletRequest("POST", REVIEWER_ENROLLMENT_PATH),
            response,
            chain,
        )

        Assert.assertNull(chain.request)
        Assert.assertEquals(404, response.status)
    }

    @Test
    fun `wrong reviewer secret and route lookalikes cannot enter the bootstrap chain`() {
        val studyId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val reviewerSecret = "reviewer-console-secret-with-at-least-32-random-chars"
        val auditService = mock(AuditService::class.java)
        val filter = newFilter(
            reviewerEnrollmentSecret = reviewerSecret,
            reviewerStudyId = studyId,
            auditService = auditService,
        )

        val wrongSecretResponse = MockHttpServletResponse()
        val wrongSecretChain = MockFilterChain()
        filter.doFilter(
            MockHttpServletRequest("POST", REVIEWER_ENROLLMENT_PATH).apply {
                addHeader(MobileApiSignatureFilter.HEADER_REVIEWER_SECRET, "wrong-secret")
            },
            wrongSecretResponse,
            wrongSecretChain,
        )
        Assert.assertNull(wrongSecretChain.request)
        Assert.assertEquals(401, wrongSecretResponse.status)
        Assert.assertNull(SecurityContextHolder.getContext().authentication)
        val auditCaptor = argumentCaptor<AuditLogEntry>()
        verify(auditService).log(auditCaptor.capture())
        Assert.assertEquals(studyId, auditCaptor.firstValue.studyId)
        Assert.assertFalse(auditCaptor.firstValue.success)
        Assert.assertEquals(401, auditCaptor.firstValue.responseCode)
        Assert.assertFalse(auditCaptor.firstValue.toString().contains(reviewerSecret))

        val lookalikeResponse = MockHttpServletResponse()
        val lookalikeChain = MockFilterChain()
        filter.doFilter(
            MockHttpServletRequest("POST", "$REVIEWER_ENROLLMENT_PATH/extra").apply {
                addHeader(MobileApiSignatureFilter.HEADER_REVIEWER_SECRET, reviewerSecret)
            },
            lookalikeResponse,
            lookalikeChain,
        )
        Assert.assertNull(lookalikeChain.request)
        Assert.assertEquals(401, lookalikeResponse.status)
        Assert.assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `reviewer reusable secret never authorizes a wrong-method request`() {
        val reviewerSecret = "reviewer-console-secret-with-at-least-32-random-chars"
        val filter = newFilter(
            reviewerEnrollmentSecret = reviewerSecret,
            reviewerStudyId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        )
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(
            MockHttpServletRequest("GET", REVIEWER_ENROLLMENT_PATH).apply {
                addHeader(MobileApiSignatureFilter.HEADER_REVIEWER_SECRET, reviewerSecret)
            },
            response,
            chain,
        )

        Assert.assertNull(chain.request)
        Assert.assertEquals(404, response.status)
        Assert.assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `exact public settings GETs do not require a distributed HMAC secret`() {
        val filter = newFilter(signingRequired = true)
        val publicPaths = listOf(
            "/chronicle/v3/study/00000000-0000-0000-0000-000000000001/settings/sensors",
            "/chronicle/v3/study/00000000-0000-0000-0000-000000000001/settings/type/AndroidSensor",
            "/chronicle/v3/study/00000000-0000-0000-0000-000000000001/settings/type/Sensor",
            PUBLIC_DATA_COLLECTION_SETTINGS_PATH,
            "/chronicle/v3/study/00000000-0000-0000-0000-000000000001/settings/type/Encryption",
        )

        publicPaths.forEach { path ->
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()
            filter.doFilter(MockHttpServletRequest("GET", path), response, chain)
            Assert.assertNotNull("Public settings GET should reach the controller chain: $path", chain.request)
            Assert.assertEquals(200, response.status)
        }
    }

    @Test
    fun `public settings exception is exact and read only`() {
        val filter = newFilter(signingRequired = true)
        val lookalikePaths = listOf(
            "$PUBLIC_DATA_COLLECTION_SETTINGS_PATH/private",
            "/chronicle/v3/study/not-a-uuid/settings/type/DataCollection",
            "/chronicle/v3/study/00000000-0000-0000-0000-000000000001/settings/type/Notifications",
        )

        lookalikePaths.forEach { path ->
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()
            filter.doFilter(MockHttpServletRequest("GET", path), response, chain)
            Assert.assertNull("Lookalike path must remain protected: $path", chain.request)
            Assert.assertEquals(401, response.status)
        }

        val writeResponse = MockHttpServletResponse()
        val writeChain = MockFilterChain()
        filter.doFilter(
            MockHttpServletRequest("PATCH", PUBLIC_DATA_COLLECTION_SETTINGS_PATH),
            writeResponse,
            writeChain,
        )
        Assert.assertNull("Settings writes must remain protected", writeChain.request)
        Assert.assertEquals(401, writeResponse.status)
    }

    @Test
    fun `device API key request reaches API key authentication without shared HMAC`() {
        val filter = newFilter()
        val request = MockHttpServletRequest("POST", MOBILE_UPLOAD_PATH).apply {
            setContent("""{"data":"test"}""".toByteArray())
            addHeader("X-Api-Key", "device-key")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNotNull(chain.request)
        Assert.assertEquals(200, response.status)
    }

    @Test
    fun testValidSignaturePasses() {
        val filter = newFilter()
        val body = """{"data":"test"}""".toByteArray()
        val timestamp = Instant.now().epochSecond.toString()
        val nonce = UUID.randomUUID().toString()

        val request = MockHttpServletRequest("POST", MOBILE_DATA_PATH)
        request.setContent(body)
        request.addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
        request.addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
        request.addHeader(
            MobileApiSignatureFilter.HEADER_SIGNATURE,
            computeSignature("POST", MOBILE_DATA_PATH, timestamp, nonce, body)
        )

        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNotNull("Request should have passed through the filter chain", chain.request)
        Assert.assertEquals(200, response.status)
    }

    @Test
    fun `previous signing secret is accepted only during configured rotation overlap`() {
        val previousSecret = "previous-mobile-signing-secret-at-least-32-bytes"
        val body = """{"data":"from-not-yet-updated-client"}""".toByteArray()
        val timestamp = Instant.now().epochSecond.toString()

        fun signedRequest(nonce: String): MockHttpServletRequest =
            MockHttpServletRequest("POST", MOBILE_DATA_PATH).apply {
                setContent(body)
                addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
                addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
                addHeader(
                    MobileApiSignatureFilter.HEADER_SIGNATURE,
                    computeSignature("POST", MOBILE_DATA_PATH, timestamp, nonce, body, previousSecret),
                )
            }

        val overlapFilter = MobileApiSignatureFilter(
            hazelcastInstance = hz,
            signingSecret = SECRET,
            signingRequired = true,
            previousSigningSecrets = listOf(previousSecret),
        )
        val overlapResponse = MockHttpServletResponse()
        val overlapChain = MockFilterChain()
        overlapFilter.doFilter(signedRequest(UUID.randomUUID().toString()), overlapResponse, overlapChain)

        Assert.assertNotNull("Previous key should work during overlap", overlapChain.request)
        Assert.assertEquals(200, overlapResponse.status)

        val finalizedResponse = MockHttpServletResponse()
        val finalizedChain = MockFilterChain()
        newFilter().doFilter(signedRequest(UUID.randomUUID().toString()), finalizedResponse, finalizedChain)

        Assert.assertNull("Previous key must stop working after overlap is removed", finalizedChain.request)
        Assert.assertEquals(401, finalizedResponse.status)
    }

    @Test
    fun testValidDeflateSignaturePassesDecodedBody() {
        val filter = newFilter()
        val decodedBody = """[{"sensor":"accelerometer","data":"repeated repeated repeated"}]"""
            .repeat(100)
            .toByteArray()
        val encodedBody = deflate(decodedBody)
        val timestamp = Instant.now().epochSecond.toString()
        val nonce = UUID.randomUUID().toString()
        val request = MockHttpServletRequest("POST", MOBILE_DATA_PATH).apply {
            setContent(encodedBody)
            addHeader("Content-Encoding", "deflate")
            addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
            addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
            addHeader(
                MobileApiSignatureFilter.HEADER_SIGNATURE,
                computeSignature("POST", MOBILE_DATA_PATH, timestamp, nonce, encodedBody),
            )
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        val downstreamRequest = chain.request as HttpServletRequest
        Assert.assertArrayEquals(decodedBody, downstreamRequest.inputStream.readBytes())
        Assert.assertNull(downstreamRequest.getHeader("Content-Encoding"))
        Assert.assertEquals(decodedBody.size, downstreamRequest.contentLength)
        Assert.assertEquals(200, response.status)
    }

    @Test
    fun testDeflateSignatureMustCoverEncodedBytes() {
        val filter = newFilter()
        val decodedBody = """{"data":"compress me"}""".repeat(100).toByteArray()
        val encodedBody = deflate(decodedBody)
        val timestamp = Instant.now().epochSecond.toString()
        val nonce = UUID.randomUUID().toString()
        val request = MockHttpServletRequest("POST", MOBILE_DATA_PATH).apply {
            setContent(encodedBody)
            addHeader("Content-Encoding", "deflate")
            addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
            addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
            addHeader(
                MobileApiSignatureFilter.HEADER_SIGNATURE,
                computeSignature("POST", MOBILE_DATA_PATH, timestamp, nonce, decodedBody),
            )
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNull(chain.request)
        Assert.assertEquals(401, response.status)
    }

    @Test
    fun testDeflateBodyDecodesWhenSignatureVerificationIsDisabled() {
        val filter = MobileApiSignatureFilter(
            hazelcastInstance = hz,
            signingSecret = "",
            signatureVerificationEnabled = false,
        )
        val decodedBody = """{"data":"local development"}""".repeat(100).toByteArray()
        val request = MockHttpServletRequest("POST", MOBILE_DATA_PATH).apply {
            setContent(deflate(decodedBody))
            addHeader("Content-Encoding", "deflate")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        val downstreamRequest = chain.request as HttpServletRequest
        Assert.assertArrayEquals(decodedBody, downstreamRequest.inputStream.readBytes())
        Assert.assertEquals(200, response.status)
    }

    @Test
    fun testMalformedDeflateBodyRejected() {
        val filter = newFilter()
        val encodedBody = "not-a-deflate-stream".toByteArray()
        val timestamp = Instant.now().epochSecond.toString()
        val nonce = UUID.randomUUID().toString()
        val request = MockHttpServletRequest("POST", MOBILE_DATA_PATH).apply {
            setContent(encodedBody)
            addHeader("Content-Encoding", "deflate")
            addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
            addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
            addHeader(
                MobileApiSignatureFilter.HEADER_SIGNATURE,
                computeSignature("POST", MOBILE_DATA_PATH, timestamp, nonce, encodedBody),
            )
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNull(chain.request)
        Assert.assertEquals(400, response.status)
    }

    @Test
    fun testUnsupportedContentEncodingRejected() {
        val filter = newFilter()
        val body = """{"data":"test"}""".toByteArray()
        val timestamp = Instant.now().epochSecond.toString()
        val nonce = UUID.randomUUID().toString()
        val request = MockHttpServletRequest("POST", MOBILE_DATA_PATH).apply {
            setContent(body)
            addHeader("Content-Encoding", "br")
            addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
            addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
            addHeader(
                MobileApiSignatureFilter.HEADER_SIGNATURE,
                computeSignature("POST", MOBILE_DATA_PATH, timestamp, nonce, body),
            )
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNull(chain.request)
        Assert.assertEquals(415, response.status)
    }

    @Test
    fun testDeflateExpansionBeyondLimitRejected() {
        val filter = MobileApiSignatureFilter(
            hazelcastInstance = hz,
            signingSecret = SECRET,
            signingRequired = true,
            maxDecodedBodyBytes = 64,
        )
        val encodedBody = deflate(ByteArray(1_024) { 65 })
        val timestamp = Instant.now().epochSecond.toString()
        val nonce = UUID.randomUUID().toString()
        val request = MockHttpServletRequest("POST", MOBILE_DATA_PATH).apply {
            setContent(encodedBody)
            addHeader("Content-Encoding", "deflate")
            addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
            addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
            addHeader(
                MobileApiSignatureFilter.HEADER_SIGNATURE,
                computeSignature("POST", MOBILE_DATA_PATH, timestamp, nonce, encodedBody),
            )
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNull(chain.request)
        Assert.assertEquals(413, response.status)
    }

    @Test
    fun testValidWithdrawalSignaturePasses() {
        val filter = newFilter()
        val body = ByteArray(0)
        val timestamp = Instant.now().epochSecond.toString()
        val nonce = UUID.randomUUID().toString()
        val request = MockHttpServletRequest("DELETE", MOBILE_WITHDRAWAL_PATH).apply {
            setContent(body)
            addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
            addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
            addHeader(
                MobileApiSignatureFilter.HEADER_SIGNATURE,
                computeSignature("DELETE", MOBILE_WITHDRAWAL_PATH, timestamp, nonce, body),
            )
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNotNull(chain.request)
        Assert.assertEquals(200, response.status)
    }

    @Test
    fun testInvalidSignatureRejected() {
        val filter = newFilter()
        val body = """{"data":"test"}""".toByteArray()
        val timestamp = Instant.now().epochSecond.toString()
        val nonce = UUID.randomUUID().toString()

        val request = MockHttpServletRequest("POST", MOBILE_DATA_PATH)
        request.setContent(body)
        request.addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
        request.addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
        request.addHeader(MobileApiSignatureFilter.HEADER_SIGNATURE, "invalid-signature")

        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNull("Request should NOT have passed through the filter chain", chain.request)
        Assert.assertEquals(401, response.status)
    }

    @Test
    fun testInvalidSignatureDoesNotConsumeNonce() {
        val filter = newFilter()
        val body = """{"data":"test"}""".toByteArray()
        val timestamp = Instant.now().epochSecond.toString()
        val nonce = UUID.randomUUID().toString()

        val invalidRequest = MockHttpServletRequest("POST", MOBILE_DATA_PATH).apply {
            setContent(body)
            addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
            addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
            addHeader(MobileApiSignatureFilter.HEADER_SIGNATURE, "invalid-signature")
        }
        filter.doFilter(invalidRequest, MockHttpServletResponse(), MockFilterChain())

        val validRequest = MockHttpServletRequest("POST", MOBILE_DATA_PATH).apply {
            setContent(body)
            addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
            addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
            addHeader(
                MobileApiSignatureFilter.HEADER_SIGNATURE,
                computeSignature("POST", MOBILE_DATA_PATH, timestamp, nonce, body),
            )
        }
        val validResponse = MockHttpServletResponse()
        val validChain = MockFilterChain()

        filter.doFilter(validRequest, validResponse, validChain)

        Assert.assertNotNull("A rejected signature must not reserve its nonce", validChain.request)
        Assert.assertEquals(200, validResponse.status)
    }

    @Test
    fun testReplayRejected() {
        val filter = newFilter()
        val body = """{"data":"test"}""".toByteArray()
        val timestamp = Instant.now().epochSecond.toString()
        val nonce = UUID.randomUUID().toString()
        val signature = computeSignature("POST", MOBILE_DATA_PATH, timestamp, nonce, body)

        // First request should pass
        val request1 = MockHttpServletRequest("POST", MOBILE_DATA_PATH)
        request1.setContent(body)
        request1.addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
        request1.addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
        request1.addHeader(MobileApiSignatureFilter.HEADER_SIGNATURE, signature)

        val response1 = MockHttpServletResponse()
        filter.doFilter(request1, response1, MockFilterChain())
        Assert.assertEquals(200, response1.status)

        // Replay with same nonce should be rejected
        val request2 = MockHttpServletRequest("POST", MOBILE_DATA_PATH)
        request2.setContent(body)
        request2.addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
        request2.addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
        request2.addHeader(MobileApiSignatureFilter.HEADER_SIGNATURE, signature)

        val response2 = MockHttpServletResponse()
        val chain2 = MockFilterChain()
        filter.doFilter(request2, response2, chain2)

        Assert.assertNull("Replay request should NOT have passed through", chain2.request)
        Assert.assertEquals(401, response2.status)
    }

    @Test
    fun testNonMobilePathBypasses() {
        val filter = newFilter()

        val request = MockHttpServletRequest("GET", "/api/web/studies")
        request.setContent(ByteArray(0))

        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNotNull("Non-mobile path should bypass the filter", chain.request)
        Assert.assertEquals(200, response.status)
    }

    @Test
    fun testStampedCookieAuthenticatedWebRequestBypassesMobileSigning() {
        val internalWebSecret = "proxy-injected-internal-web-secret-32bytes!!"
        val request = MockHttpServletRequest("POST", WEB_ACCESS_CODE_PATH).apply {
            serverName = "inventory-rendered-aws-host.example"
            addHeader("X-Chronicle-Internal-Web", internalWebSecret)
            setCookies(Cookie("chronicle_auth", "browser-jwt-placeholder"))
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        newFilter(internalWebSecret = internalWebSecret).doFilter(request, response, chain)

        Assert.assertNotNull(chain.request)
        Assert.assertEquals(200, response.status)
    }

    @Test
    fun testStampedBearerAuthenticatedWebRequestBypassesMobileSigning() {
        val internalWebSecret = "proxy-injected-internal-web-secret-32bytes!!"
        val request = MockHttpServletRequest("GET", MOBILE_STATUS_PATH).apply {
            addHeader("X-Chronicle-Internal-Web", internalWebSecret)
            addHeader("Authorization", "Bearer browser-jwt-placeholder")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        newFilter(internalWebSecret = internalWebSecret).doFilter(request, response, chain)

        Assert.assertNotNull(chain.request)
        Assert.assertEquals(200, response.status)
    }

    @Test
    fun testProxyMarkerWithoutBrowserCredentialCannotBypassSigning() {
        val request = MockHttpServletRequest("POST", MOBILE_DATA_PATH).apply {
            addHeader("X-Chronicle-Internal-Web", "true")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        newFilter().doFilter(request, response, chain)

        Assert.assertNull(chain.request)
        Assert.assertEquals(401, response.status)
    }

    @Test
    fun testMobileCredentialUsesApiKeyPathEvenWhenWebMarkerIsPresent() {
        val request = MockHttpServletRequest("POST", MOBILE_DATA_PATH).apply {
            addHeader("X-Chronicle-Internal-Web", "true")
            addHeader("X-Api-Key", "stolen-device-key")
            setCookies(Cookie("chronicle_auth", "fake-browser-cookie"))
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        newFilter().doFilter(request, response, chain)

        Assert.assertNotNull(chain.request)
        Assert.assertEquals(200, response.status)
    }

    @Test
    fun testInternalWebSecretMarkerBypassesSigningWhenConfigured() {
        val internalWebSecret = "proxy-injected-internal-web-secret-32bytes!!"
        val request = MockHttpServletRequest("GET", MOBILE_STATUS_PATH).apply {
            addHeader("X-Chronicle-Internal-Web", internalWebSecret)
            addHeader("Authorization", "Bearer browser-jwt-placeholder")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        newFilter(internalWebSecret = internalWebSecret).doFilter(request, response, chain)

        Assert.assertNotNull("Correct internal-web secret must bypass mobile signing", chain.request)
        Assert.assertEquals(200, response.status)
    }

    @Test
    fun testLegacyTrueMarkerRejectedWhenInternalWebSecretIsBlank() {
        val request = MockHttpServletRequest("GET", MOBILE_STATUS_PATH).apply {
            addHeader("X-Chronicle-Internal-Web", "true")
            addHeader("Authorization", "Bearer browser-jwt-placeholder")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        newFilter().doFilter(request, response, chain)

        Assert.assertNull("A literal marker must never act as a credential", chain.request)
        Assert.assertEquals(401, response.status)
    }

    @Test
    fun testLegacyTrueMarkerRejectedWhenInternalWebSecretConfigured() {
        val request = MockHttpServletRequest("GET", MOBILE_STATUS_PATH).apply {
            addHeader("X-Chronicle-Internal-Web", "true")
            addHeader("Authorization", "Bearer browser-jwt-placeholder")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        newFilter(internalWebSecret = "proxy-injected-internal-web-secret-32bytes!!")
            .doFilter(request, response, chain)

        Assert.assertNull("Guessable \"true\" marker must not bypass when a secret is set", chain.request)
        Assert.assertEquals(401, response.status)
    }

    @Test
    fun testWrongInternalWebSecretMarkerRejected() {
        val request = MockHttpServletRequest("GET", MOBILE_STATUS_PATH).apply {
            addHeader("X-Chronicle-Internal-Web", "attacker-guessed-value")
            addHeader("Authorization", "Bearer browser-jwt-placeholder")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        newFilter(internalWebSecret = "proxy-injected-internal-web-secret-32bytes!!")
            .doFilter(request, response, chain)

        Assert.assertNull("A forged internal-web marker must not bypass signing", chain.request)
        Assert.assertEquals(401, response.status)
    }

    @Test
    fun testEnrollmentCannotUseStampedWebBypass() {
        val request = MockHttpServletRequest("POST", MOBILE_ENROLLMENT_PATH).apply {
            addHeader("X-Chronicle-Internal-Web", "true")
            setCookies(Cookie("chronicle_auth", "fake-browser-cookie"))
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        newFilter().doFilter(request, response, chain)

        Assert.assertNull(chain.request)
        Assert.assertEquals(401, response.status)
    }

    @Test
    fun testMissingHeadersWhenSigningNotRequired() {
        val filter = newFilter(signingRequired = false)

        val request = MockHttpServletRequest("POST", MOBILE_DATA_PATH)
        request.setContent("""{"data":"test"}""".toByteArray())

        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNotNull("Unsigned request should pass when signing not required", chain.request)
        Assert.assertEquals(200, response.status)
    }

    @Test
    fun testMissingHeadersWhenSigningRequired() {
        val filter = newFilter(signingRequired = true)

        val request = MockHttpServletRequest("POST", MOBILE_DATA_PATH)
        request.setContent("""{"data":"test"}""".toByteArray())

        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNull("Unsigned request should be rejected when signing required", chain.request)
        Assert.assertEquals(401, response.status)
    }

    @Test
    fun testEmptyBodyWithValidSignature() {
        val filter = newFilter()
        val body = ByteArray(0)
        val timestamp = Instant.now().epochSecond.toString()
        val nonce = UUID.randomUUID().toString()

        val request = MockHttpServletRequest("GET", MOBILE_STATUS_PATH)
        request.setContent(body)
        request.addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
        request.addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
        request.addHeader(
            MobileApiSignatureFilter.HEADER_SIGNATURE,
            computeSignature("GET", MOBILE_STATUS_PATH, timestamp, nonce, body)
        )

        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNotNull("Empty body request with valid signature should pass", chain.request)
        Assert.assertEquals(200, response.status)
    }

    @Test
    fun testTamperedBodyRejected() {
        val filter = newFilter()
        val originalBody = """{"data":"original"}""".toByteArray()
        val timestamp = Instant.now().epochSecond.toString()
        val nonce = UUID.randomUUID().toString()

        // Compute signature for original body
        val signature = computeSignature("POST", MOBILE_DATA_PATH, timestamp, nonce, originalBody)

        // But send tampered body
        val tamperedBody = """{"data":"tampered"}""".toByteArray()

        val request = MockHttpServletRequest("POST", MOBILE_DATA_PATH)
        request.setContent(tamperedBody)
        request.addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
        request.addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
        request.addHeader(MobileApiSignatureFilter.HEADER_SIGNATURE, signature)

        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNull("Tampered body should be rejected", chain.request)
        Assert.assertEquals(401, response.status)
    }

    @Test
    fun testExpiredTimestampRejected() {
        val filter = newFilter()
        val body = """{"data":"test"}""".toByteArray()
        // Timestamp from 10 minutes ago (beyond the 5+0.5 min window)
        val timestamp = (Instant.now().epochSecond - 700).toString()
        val nonce = UUID.randomUUID().toString()

        val request = MockHttpServletRequest("POST", MOBILE_DATA_PATH)
        request.setContent(body)
        request.addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
        request.addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
        request.addHeader(
            MobileApiSignatureFilter.HEADER_SIGNATURE,
            computeSignature("POST", MOBILE_DATA_PATH, timestamp, nonce, body)
        )

        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNull("Expired timestamp should be rejected", chain.request)
        Assert.assertEquals(401, response.status)
    }

    @Test
    fun testFutureTimestampRejected() {
        val filter = newFilter()
        val body = """{"data":"test"}""".toByteArray()
        // Timestamp 10 minutes in the future (beyond clock skew)
        val timestamp = (Instant.now().epochSecond + 600).toString()
        val nonce = UUID.randomUUID().toString()

        val request = MockHttpServletRequest("POST", MOBILE_DATA_PATH)
        request.setContent(body)
        request.addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
        request.addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
        request.addHeader(
            MobileApiSignatureFilter.HEADER_SIGNATURE,
            computeSignature("POST", MOBILE_DATA_PATH, timestamp, nonce, body)
        )

        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNull("Future timestamp should be rejected", chain.request)
        Assert.assertEquals(401, response.status)
    }

    @Test
    fun testOutOfRangeTimestampsAreRejectedWithoutServerError() {
        listOf(Long.MIN_VALUE, Long.MAX_VALUE).forEach { extreme ->
            val request = MockHttpServletRequest("POST", MOBILE_DATA_PATH).apply {
                setContent(ByteArray(0))
                addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, extreme.toString())
                addHeader(MobileApiSignatureFilter.HEADER_NONCE, UUID.randomUUID().toString())
                addHeader(MobileApiSignatureFilter.HEADER_SIGNATURE, "not-evaluated")
            }
            val response = MockHttpServletResponse()

            newFilter().doFilter(request, response, MockFilterChain())

            Assert.assertEquals(400, response.status)
        }
    }

    @Test
    fun testInvalidTimestampFormatRejected() {
        val filter = newFilter()
        val body = """{"data":"test"}""".toByteArray()
        val nonce = UUID.randomUUID().toString()

        val request = MockHttpServletRequest("POST", MOBILE_DATA_PATH)
        request.setContent(body)
        request.addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, "not-a-number")
        request.addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
        request.addHeader(MobileApiSignatureFilter.HEADER_SIGNATURE, "some-signature")

        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNull("Invalid timestamp format should be rejected", chain.request)
        Assert.assertEquals(400, response.status)
    }

    @Test
    fun testInvalidNonceFormatRejected() {
        val filter = newFilter()
        val body = """{"data":"test"}""".toByteArray()
        val timestamp = Instant.now().epochSecond.toString()

        val request = MockHttpServletRequest("POST", MOBILE_DATA_PATH)
        request.setContent(body)
        request.addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
        request.addHeader(MobileApiSignatureFilter.HEADER_NONCE, "not-a-uuid")
        request.addHeader(MobileApiSignatureFilter.HEADER_SIGNATURE, "some-signature")

        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNull("Invalid nonce format should be rejected", chain.request)
        Assert.assertEquals(400, response.status)
    }

    @Test
    fun testDifferentHttpMethodsProdDifferentSignatures() {
        val body = """{"data":"test"}""".toByteArray()
        val timestamp = Instant.now().epochSecond.toString()
        val nonce = UUID.randomUUID().toString()

        val sigGet = computeSignature("GET", MOBILE_DATA_PATH, timestamp, nonce, body)
        val sigPost = computeSignature("POST", MOBILE_DATA_PATH, timestamp, nonce, body)
        val sigPut = computeSignature("PUT", MOBILE_DATA_PATH, timestamp, nonce, body)

        Assert.assertNotEquals("GET and POST should produce different signatures", sigGet, sigPost)
        Assert.assertNotEquals("GET and PUT should produce different signatures", sigGet, sigPut)
        Assert.assertNotEquals("POST and PUT should produce different signatures", sigPost, sigPut)
    }

    @Test
    fun testDifferentPathsProduceDifferentSignatures() {
        val body = """{"data":"test"}""".toByteArray()
        val timestamp = Instant.now().epochSecond.toString()
        val nonce = UUID.randomUUID().toString()

        val sig1 = computeSignature("POST", MOBILE_DATA_PATH, timestamp, nonce, body)
        val sig2 = computeSignature("POST", MOBILE_UPLOAD_PATH, timestamp, nonce, body)

        Assert.assertNotEquals("Different paths should produce different signatures", sig1, sig2)
    }

    @Test
    fun testPartialHeadersMissingSignatureWhenRequired() {
        val filter = newFilter(signingRequired = true)
        val body = """{"data":"test"}""".toByteArray()
        val timestamp = Instant.now().epochSecond.toString()
        val nonce = UUID.randomUUID().toString()

        val request = MockHttpServletRequest("POST", MOBILE_DATA_PATH)
        request.setContent(body)
        request.addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
        request.addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
        // Missing HEADER_SIGNATURE

        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNull("Missing signature header should be rejected when required", chain.request)
        Assert.assertEquals(401, response.status)
    }

    @Test
    fun testPartialHeadersMissingTimestampWhenRequired() {
        val filter = newFilter(signingRequired = true)
        val body = """{"data":"test"}""".toByteArray()
        val nonce = UUID.randomUUID().toString()

        val request = MockHttpServletRequest("POST", MOBILE_DATA_PATH)
        request.setContent(body)
        // Missing HEADER_TIMESTAMP
        request.addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
        request.addHeader(MobileApiSignatureFilter.HEADER_SIGNATURE, "some-sig")

        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNull("Missing timestamp header should be rejected when required", chain.request)
        Assert.assertEquals(401, response.status)
    }

    @Test
    fun testPartialHeadersMissingNonceWhenRequired() {
        val filter = newFilter(signingRequired = true)
        val body = """{"data":"test"}""".toByteArray()
        val timestamp = Instant.now().epochSecond.toString()

        val request = MockHttpServletRequest("POST", MOBILE_DATA_PATH)
        request.setContent(body)
        request.addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
        // Missing HEADER_NONCE
        request.addHeader(MobileApiSignatureFilter.HEADER_SIGNATURE, "some-sig")

        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNull("Missing nonce header should be rejected when required", chain.request)
        Assert.assertEquals(401, response.status)
    }

    @Test
    fun testWebApiPathBypasses() {
        val filter = newFilter()

        val request = MockHttpServletRequest("POST", "/api/web/data")
        request.setContent("""{"data":"test"}""".toByteArray())

        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNotNull("Web API path should bypass the filter", chain.request)
        Assert.assertEquals(200, response.status)
    }

    @Test
    fun testRootPathBypasses() {
        val filter = newFilter()

        val request = MockHttpServletRequest("GET", "/health")
        request.setContent(ByteArray(0))

        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNotNull("Root path should bypass the filter", chain.request)
        Assert.assertEquals(200, response.status)
    }

    @Test
    fun testTimestampWithinClockSkewPasses() {
        val filter = newFilter()
        val body = """{"data":"test"}""".toByteArray()
        // Timestamp 20 seconds in the future (within 30s clock skew)
        val timestamp = (Instant.now().epochSecond + 20).toString()
        val nonce = UUID.randomUUID().toString()

        val request = MockHttpServletRequest("POST", MOBILE_DATA_PATH)
        request.setContent(body)
        request.addHeader(MobileApiSignatureFilter.HEADER_TIMESTAMP, timestamp)
        request.addHeader(MobileApiSignatureFilter.HEADER_NONCE, nonce)
        request.addHeader(
            MobileApiSignatureFilter.HEADER_SIGNATURE,
            computeSignature("POST", MOBILE_DATA_PATH, timestamp, nonce, body)
        )

        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        Assert.assertNotNull("Timestamp within clock skew should pass", chain.request)
        Assert.assertEquals(200, response.status)
    }
}
