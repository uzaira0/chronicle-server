/*
 * Copyright (C) 2024. Chronicle.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.openlattice.chronicle.filters

import com.openlattice.chronicle.apikey.ApiKeyInfo
import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.util.DeviceIdUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Unit tests for [ApiKeyAuthenticationFilter] mobile-key path-binding enforcement.
 *
 * A mobile API key is issued for a specific (studyId, participantId, deviceId)
 * tuple. The filter must reject any request whose path doesn't match the key's
 * binding, and emit a `path_mismatch` metric so anomaly detection can react.
 *
 * Admin keys (no participantId) bypass the binding check.
 */
class ApiKeyAuthenticationFilterTest {

    private val apiKeyService: ApiKeyService = Mockito.mock(ApiKeyService::class.java)
    private lateinit var filter: ApiKeyAuthenticationFilter

    private val studyA = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val studyB = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val sourceDeviceX = "device-install-x"
    private val deviceX = DeviceIdUtils.deriveDeviceId(studyA, "participant-p", sourceDeviceX)
    private val participantP = "participant-p"
    private val participantQ = "participant-q"
    private val withdrawalRequestId = UUID.fromString("aaaaaaaa-3333-4333-8333-333333333333")

    @Before
    fun setUp() {
        // honeyTokenService=null skips the canary check; we only test the binding logic.
        filter = ApiKeyAuthenticationFilter(apiKeyService, honeyTokenService = null)
    }

    private fun mobileKey(studyId: UUID, participantId: String, deviceId: UUID): ApiKeyInfo =
        ApiKeyInfo(
            keyId = UUID.randomUUID(),
            studyId = studyId,
            prefix = "abc12345",
            name = "device-test",
            scope = ApiKeyScope.WRITE,
            createdAt = OffsetDateTime.now(),
            expiresAt = OffsetDateTime.now().plusDays(365),
            participantId = participantId,
            deviceId = deviceId
        )

    private fun adminKey(studyId: UUID): ApiKeyInfo =
        ApiKeyInfo(
            keyId = UUID.randomUUID(),
            studyId = studyId,
            prefix = "admin000",
            name = "admin",
            scope = ApiKeyScope.ADMIN,
            createdAt = OffsetDateTime.now(),
            expiresAt = OffsetDateTime.now().plusDays(365),
            participantId = null,
            deviceId = null
        )

    private fun runFilter(
        uri: String,
        rawKey: String = "ck_abc12345_keyvalue",
        sourceDeviceId: String? = sourceDeviceX,
        method: String = "POST",
        withdrawalRequestIdHeader: String? = withdrawalRequestId.toString(),
    ): Pair<MockHttpServletResponse, MockFilterChain> {
        val request = MockHttpServletRequest(method, uri).apply {
            requestURI = uri
            addHeader("X-Api-Key", rawKey)
            sourceDeviceId?.let { addHeader("X-Chronicle-Device-Id", it) }
            withdrawalRequestIdHeader?.let { addHeader("X-Chronicle-Withdrawal-Request-Id", it) }
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return response to chain
    }

    private fun pathMismatchCount(prefix: String): Double =
        ChronicleMetrics.apiKeyUsageTotal.labels(prefix, "path_mismatch").get()

    @Test
    fun mobileKeyOnMatchingPathPasses() {
        Mockito.`when`(apiKeyService.authenticateApiKey("ck_abc12345_keyvalue"))
            .thenReturn(mobileKey(studyA, participantP, deviceX))
        val before = pathMismatchCount("abc12345")

        val (response, chain) = runFilter("/chronicle/v4/study/$studyA/participant/$participantP/android")

        assertEquals("expected filter chain to proceed (200 default)", 200, response.status)
        assertNotNull("filter chain must be invoked on success", chain.request)
        assertEquals("path_mismatch counter must not increment on success", before, pathMismatchCount("abc12345"), 0.0)
    }

    @Test
    fun mobileKeyOnCurrentEnrollmentWithdrawalPathPassesWithMatchingDevice() {
        Mockito.`when`(
            apiKeyService.authenticateWithdrawalApiKey("ck_abc12345_keyvalue", withdrawalRequestId),
        )
            .thenReturn(mobileKey(studyA, participantP, deviceX))

        val (response, chain) = runFilter(
            ApiKeyAuthenticationFilter.CURRENT_ENROLLMENT_PATH,
            method = "DELETE",
        )

        assertEquals(200, response.status)
        assertNotNull(chain.request)
    }

    @Test
    fun mobileKeyOnCurrentEnrollmentWithdrawalPathRejectsDifferentDevice() {
        Mockito.`when`(
            apiKeyService.authenticateWithdrawalApiKey("ck_abc12345_keyvalue", withdrawalRequestId),
        )
            .thenReturn(mobileKey(studyA, participantP, deviceX))

        val (response, chain) = runFilter(
            ApiKeyAuthenticationFilter.CURRENT_ENROLLMENT_PATH,
            sourceDeviceId = "different-device-install",
            method = "DELETE",
        )

        assertEquals(403, response.status)
        assertNull(chain.request)
    }

    @Test
    fun withdrawalWithoutStableRequestIdIsRejectedBeforeCredentialLookup() {
        val (response, chain) = runFilter(
            ApiKeyAuthenticationFilter.CURRENT_ENROLLMENT_PATH,
            method = "DELETE",
            withdrawalRequestIdHeader = null,
        )

        assertEquals(401, response.status)
        assertNull(chain.request)
        Mockito.verifyNoInteractions(apiKeyService)
    }

    @Test
    fun withdrawalWithNonCanonicalRequestIdIsRejectedBeforeCredentialLookup() {
        val (response, chain) = runFilter(
            ApiKeyAuthenticationFilter.CURRENT_ENROLLMENT_PATH,
            method = "DELETE",
            withdrawalRequestIdHeader = withdrawalRequestId.toString().uppercase(),
        )

        assertEquals(401, response.status)
        assertNull(chain.request)
        Mockito.verifyNoInteractions(apiKeyService)
    }

    @Test
    fun revokedCredentialCannotInitiateWithdrawalWithoutExactStoredIntent() {
        Mockito.`when`(
            apiKeyService.authenticateWithdrawalApiKey("ck_abc12345_keyvalue", withdrawalRequestId),
        ).thenReturn(null)

        val (response, chain) = runFilter(
            ApiKeyAuthenticationFilter.CURRENT_ENROLLMENT_PATH,
            method = "DELETE",
        )

        assertEquals(401, response.status)
        assertNull(chain.request)
        Mockito.verify(apiKeyService)
            .authenticateWithdrawalApiKey("ck_abc12345_keyvalue", withdrawalRequestId)
    }

    @Test
    fun mobileKeyOnDifferentStudyRejected() {
        Mockito.`when`(apiKeyService.authenticateApiKey("ck_abc12345_keyvalue"))
            .thenReturn(mobileKey(studyA, participantP, deviceX))
        val before = pathMismatchCount("abc12345")

        val (response, chain) = runFilter("/chronicle/v4/study/$studyB/participant/$participantP/android")

        assertEquals(403, response.status)
        assertNull("filter chain must NOT proceed when key path-binding mismatches", chain.request)
        assertEquals("path_mismatch counter should increment by 1", before + 1.0, pathMismatchCount("abc12345"), 0.0)
    }

    @Test
    fun mobileKeyOnDifferentParticipantRejected() {
        Mockito.`when`(apiKeyService.authenticateApiKey("ck_abc12345_keyvalue"))
            .thenReturn(mobileKey(studyA, participantP, deviceX))
        val before = pathMismatchCount("abc12345")

        val (response, _) = runFilter("/chronicle/v4/study/$studyA/participant/$participantQ/android")

        assertEquals(403, response.status)
        assertEquals(before + 1.0, pathMismatchCount("abc12345"), 0.0)
    }

    @Test
    fun mobileKeyWithoutDeviceHeaderRejected() {
        Mockito.`when`(apiKeyService.authenticateApiKey("ck_abc12345_keyvalue"))
            .thenReturn(mobileKey(studyA, participantP, deviceX))

        val (response, chain) = runFilter(
            "/chronicle/v4/study/$studyA/participant/$participantP/android",
            sourceDeviceId = null,
        )

        assertEquals(403, response.status)
        assertNull(chain.request)
    }

    @Test
    fun mobileKeyForDifferentDeviceRejected() {
        Mockito.`when`(apiKeyService.authenticateApiKey("ck_abc12345_keyvalue"))
            .thenReturn(mobileKey(studyA, participantP, deviceX))

        val (response, chain) = runFilter(
            "/chronicle/v4/study/$studyA/participant/$participantP/android",
            sourceDeviceId = "different-device-install",
        )

        assertEquals(403, response.status)
        assertNull(chain.request)
    }

    @Test
    fun mobileKeyOnNonMobilePathRejected() {
        Mockito.`when`(apiKeyService.authenticateApiKey("ck_abc12345_keyvalue"))
            .thenReturn(mobileKey(studyA, participantP, deviceX))
        val before = pathMismatchCount("abc12345")

        // Admin/web path — the regex shouldn't match this at all.
        val (response, _) = runFilter("/chronicle/api/web/studies/$studyA")

        assertEquals(403, response.status)
        assertEquals(before + 1.0, pathMismatchCount("abc12345"), 0.0)
    }

    @Test
    fun adminKeyBypassesPathBinding() {
        // Admin key has no participantId — the binding check must short-circuit.
        Mockito.`when`(apiKeyService.authenticateApiKey("ck_abc12345_keyvalue"))
            .thenReturn(adminKey(studyA))

        // Request path doesn't even match the mobile regex — should still pass for admin.
        val (response, _) = runFilter("/chronicle/api/web/studies/$studyA")

        assertEquals(200, response.status)
    }

    @Test
    fun mobileKeyOnV3PathMatching() {
        // The regex covers both /v3/ and /v4/ — verify v3 still works for mobile keys.
        Mockito.`when`(apiKeyService.authenticateApiKey("ck_abc12345_keyvalue"))
            .thenReturn(mobileKey(studyA, participantP, deviceX))

        val (response, _) = runFilter("/chronicle/v3/study/$studyA/participant/$participantP/android/somedevice")

        assertEquals(200, response.status)
    }
}
