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
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.controllers.kAny
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.util.DeviceIdUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.time.OffsetDateTime
import java.util.EnumSet
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
    private val authorizationManager: AuthorizationManager = Mockito.mock(AuthorizationManager::class.java)
    private val keyCreator = "key-creator"

    /** What the researcher key's creator holds on studyA right now. */
    private var creatorHolds: Set<Permission> = EnumSet.of(Permission.READ, Permission.WRITE, Permission.OWNER)

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
        filter = ApiKeyAuthenticationFilter(apiKeyService, authorizationManager, honeyTokenService = null)
        TestSecurityUtils.initPrincipalsIfNeeded()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenAnswer { call ->
            call.getArgument<AclKey>(0) == AclKey(studyA) &&
                call.getArgument<Set<Principal>>(1).contains(Principal(PrincipalType.USER, keyCreator)) &&
                creatorHolds.containsAll(call.getArgument<EnumSet<Permission>>(2))
        }
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
            deviceId = null,
            createdBy = keyCreator,
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

    private fun researcherKey(scope: ApiKeyScope): ApiKeyInfo = adminKey(studyA).copy(scope = scope)

    private fun researcherStatus(scope: ApiKeyScope, method: String, uri: String): Int {
        Mockito.`when`(apiKeyService.authenticateApiKey("ck_abc12345_keyvalue")).thenReturn(researcherKey(scope))
        try {
            val (response, chain) = runFilter(uri, method = method, sourceDeviceId = null)
            assertEquals("chain runs only when the filter admits", response.status == 200, chain.request != null)
            return response.status
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    @Test
    fun researcherKeyRouteScopeFollowsPermissionsNotHttpMethod() {
        val study = "/chronicle/v3/study/$studyA"
        val exportId = UUID.randomUUID()
        val reads = listOf(
            "GET" to study,
            "GET" to "$study/participants",
            "GET" to "$study/participants/data",
            "POST" to "$study/export/async",
            "GET" to "$study/export/$exportId/download",
        )
        val writes = listOf(
            "POST" to "$study/participant",
            "PATCH" to "$study/participant/$participantP/status",
            "PATCH" to "$study/settings/type/DataCollection",
            "POST" to "/chronicle/v3/survey/$studyA/questionnaire",
        )
        val deletions = listOf(
            "DELETE" to "$study/participants",
            "POST" to "$study/participants/purge",
        )
        val granted = mapOf(
            ApiKeyScope.READ_ONLY to reads,
            ApiKeyScope.WRITE to reads + writes,
            ApiKeyScope.ADMIN to reads + writes + deletions,
        )
        for (scope in ApiKeyScope.values()) {
            for ((method, uri) in reads + writes + deletions) {
                val expected = if ((method to uri) in granted.getValue(scope)) 200 else 403
                assertEquals("$scope $method $uri", expected, researcherStatus(scope, method, uri))
            }
        }
    }

    @Test
    fun researcherKeyActsWithAtMostItsCreatorsCurrentAccess() {
        val study = "/chronicle/v3/study/$studyA"
        val deleteParticipants = "DELETE" to "$study/participants"
        val modify = "PATCH" to "$study/participant/$participantP/status"
        val read = "GET" to study
        fun status(scope: ApiKeyScope, route: Pair<String, String>) = researcherStatus(scope, route.first, route.second)

        // An OWNER's ADMIN key may delete participants; its READ_ONLY key still cannot write.
        assertEquals(200, status(ApiKeyScope.ADMIN, deleteParticipants))
        assertEquals(403, status(ApiKeyScope.READ_ONLY, modify))

        // Creator demoted to a WRITE collaborator: the ADMIN key works at WRITE and no longer deletes.
        creatorHolds = EnumSet.of(Permission.READ, Permission.WRITE)
        assertEquals(403, status(ApiKeyScope.ADMIN, deleteParticipants))
        assertEquals(200, status(ApiKeyScope.ADMIN, modify))

        // Creator's ACL removed: a route the key reached before now answers 403, and no identity is set.
        creatorHolds = emptySet()
        Mockito.`when`(apiKeyService.authenticateApiKey("ck_abc12345_keyvalue")).thenReturn(researcherKey(ApiKeyScope.ADMIN))
        val (response, chain) = runFilter(read.second, method = read.first, sourceDeviceId = null)
        assertEquals(403, response.status)
        assertEquals("The person who created this API key no longer has access to this study", response.errorMessage)
        assertNull(chain.request)
        assertNull(SecurityContextHolder.getContext().authentication)

        // A key with no recorded creator is refused outright.
        creatorHolds = EnumSet.allOf(Permission::class.java)
        Mockito.`when`(apiKeyService.authenticateApiKey("ck_abc12345_keyvalue"))
            .thenReturn(researcherKey(ApiKeyScope.READ_ONLY).copy(createdBy = null))
        assertEquals(403, runFilter(read.second, method = read.first, sourceDeviceId = null).first.status)

        // Device keys do not depend on a creator's access.
        creatorHolds = emptySet()
        Mockito.`when`(apiKeyService.authenticateApiKey("ck_abc12345_keyvalue"))
            .thenReturn(mobileKey(studyA, participantP, deviceX).copy(createdBy = null))
        assertEquals(200, runFilter("/chronicle/v4/study/$studyA/participant/$participantP/android").first.status)
        SecurityContextHolder.clearContext()
    }

    @Test
    fun researcherKeyIsRefusedOnAnotherStudyAndOffItsRouteTable() {
        val other = "/chronicle/v3/study/$studyB"
        val study = "/chronicle/v3/study/$studyA"
        val keyId = UUID.randomUUID()
        val refused = listOf(
            // another study
            "GET" to other,
            "GET" to "$other/participants/data",
            "POST" to "$other/export/async",
            // key management, ACLs and roles, study create/delete, admin, organizations, principals, auth
            "POST" to "$study/api-keys",
            "GET" to "$study/api-keys",
            "DELETE" to "$study/api-keys/$keyId",
            "POST" to "$study/api-keys/$keyId/rotate",
            "GET" to "$study/permissions",
            "POST" to "$study/permissions",
            "POST" to "$study/roles",
            "PATCH" to "/chronicle/v3/permissions",
            "POST" to "/chronicle/v3/study",
            "PATCH" to study,
            "DELETE" to study,
            "DELETE" to "$study/schedule-delete",
            "POST" to "$study/webhooks",
            "POST" to "/chronicle/v3/admin/users/search",
            "GET" to "/chronicle/v3/admin/principals",
            "GET" to "/chronicle/v3/organization/$studyA",
            "POST" to "/chronicle/v3/auth/refresh",
            "POST" to "/chronicle/v3/admin/tokens/revoke-self",
            // unlisted forms of listed routes
            "HEAD" to study,
            "GET" to "$study/",
            "GET" to "/v3/study/$studyA",
            "GET" to "/chronicle/api/web/studies/$studyA",
        )
        for ((method, uri) in refused) {
            assertEquals("ADMIN $method $uri", 403, researcherStatus(ApiKeyScope.ADMIN, method, uri))
        }
    }

    @Test
    fun mobileKeyOnV3PathMatching() {
        // The regex covers both /v3/ and /v4/ — verify v3 still works for mobile keys.
        Mockito.`when`(apiKeyService.authenticateApiKey("ck_abc12345_keyvalue"))
            .thenReturn(mobileKey(studyA, participantP, deviceX))

        val (response, _) = runFilter("/chronicle/v3/study/$studyA/participant/$participantP/android/somedevice")

        assertEquals(200, response.status)
    }

    @Test
    fun deviceOrResearcherKeySentWithABearerTokenOrAuthCookieIsRefusedBeforeLookup() {
        SecurityContextHolder.clearContext()
        Mockito.`when`(apiKeyService.authenticateApiKey("ck_abc12345_keyvalue"))
            .thenReturn(mobileKey(studyA, participantP, deviceX))
        Mockito.`when`(apiKeyService.authenticateApiKey("ck_admin000_keyvalue")).thenReturn(adminKey(studyA))
        val credentials = listOf<MockHttpServletRequest.() -> Unit>(
            { addHeader("Authorization", "Bearer person-token") },
            { setCookies(jakarta.servlet.http.Cookie("chronicle_auth", "person-token")) },
            { setCookies(jakarta.servlet.http.Cookie("authorization", "Bearer person-token")) },
        )
        for ((uri, rawKey) in listOf(
            "/chronicle/v3/study/$studyA/participant/$participantP/android" to "ck_abc12345_keyvalue",
            "/chronicle/v3/study/$studyA" to "ck_admin000_keyvalue",
        )) {
            for (credential in credentials) {
                val request = MockHttpServletRequest("GET", uri).apply {
                    requestURI = uri
                    addHeader("X-Api-Key", rawKey)
                    addHeader("X-Chronicle-Device-Id", sourceDeviceX)
                    credential()
                }
                val response = MockHttpServletResponse()
                val chain = MockFilterChain()
                filter.doFilter(request, response, chain)
                assertEquals(400, response.status)
                assertNull(chain.request)
                assertNull(SecurityContextHolder.getContext().authentication)
            }
        }
        Mockito.verify(apiKeyService, Mockito.never()).authenticateApiKey(Mockito.anyString())
    }
}
