package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.apikey.ApiKeyCreateRequest
import com.openlattice.chronicle.apikey.ApiKeyCreateResponse
import com.openlattice.chronicle.apikey.ApiKeyInfo
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import java.util.*

class ApiKeyControllerTest {

    private val apiKeyService = Mockito.mock(ApiKeyService::class.java)
    private val controller = ApiKeyController(apiKeyService)

    @Before
    fun setUp() {
        TestSecurityUtils.setupSecurityContext()
    }

    // --- Constructor ---

    @Test
    fun testControllerConstructsSuccessfully() {
        assertNotNull(controller)
    }

    @Test
    fun testControllerAcceptsApiKeyService() {
        val svc = Mockito.mock(ApiKeyService::class.java)
        val ctrl = ApiKeyController(svc)
        assertNotNull(ctrl)
    }

    // --- createApiKey ---

    @Test
    fun testCreateApiKeyDelegatesToService() {
        val studyId = UUID.randomUUID()
        val request = Mockito.mock(ApiKeyCreateRequest::class.java)
        val response = Mockito.mock(ApiKeyCreateResponse::class.java)
        Mockito.`when`(apiKeyService.createApiKey(kEq(studyId), kAnyString(), kEq(request)))
            .thenReturn(response)

        val result = controller.createApiKey(studyId, request)
        assertNotNull(result)
    }

    @Test
    fun testCreateApiKeyReturnsServiceResponse() {
        val studyId = UUID.randomUUID()
        val request = Mockito.mock(ApiKeyCreateRequest::class.java)
        val response = Mockito.mock(ApiKeyCreateResponse::class.java)
        Mockito.`when`(apiKeyService.createApiKey(kEq(studyId), kAnyString(), kEq(request)))
            .thenReturn(response)

        val result = controller.createApiKey(studyId, request)
        assertSame(response, result)
    }

    @Test
    fun testCreateApiKeyPassesStudyId() {
        val studyId = UUID.randomUUID()
        val request = Mockito.mock(ApiKeyCreateRequest::class.java)
        val response = Mockito.mock(ApiKeyCreateResponse::class.java)
        Mockito.`when`(apiKeyService.createApiKey(kEq(studyId), kAnyString(), kEq(request)))
            .thenReturn(response)

        controller.createApiKey(studyId, request)
        verify(apiKeyService).createApiKey(kEq(studyId), kAnyString(), kEq(request))
    }

    @Test(expected = RuntimeException::class)
    fun testCreateApiKeyPropagatesServiceException() {
        val studyId = UUID.randomUUID()
        val request = Mockito.mock(ApiKeyCreateRequest::class.java)
        Mockito.`when`(apiKeyService.createApiKey(kEq(studyId), kAnyString(), kEq(request)))
            .thenThrow(RuntimeException("create failed"))

        controller.createApiKey(studyId, request)
    }

    @Test
    fun testCreateApiKeyForDifferentStudies() {
        val studyId1 = UUID.randomUUID()
        val studyId2 = UUID.randomUUID()
        val request = Mockito.mock(ApiKeyCreateRequest::class.java)
        val response1 = Mockito.mock(ApiKeyCreateResponse::class.java)
        val response2 = Mockito.mock(ApiKeyCreateResponse::class.java)
        Mockito.`when`(apiKeyService.createApiKey(kEq(studyId1), kAnyString(), kEq(request)))
            .thenReturn(response1)
        Mockito.`when`(apiKeyService.createApiKey(kEq(studyId2), kAnyString(), kEq(request)))
            .thenReturn(response2)

        assertSame(response1, controller.createApiKey(studyId1, request))
        assertSame(response2, controller.createApiKey(studyId2, request))
    }

    // --- listApiKeys ---

    @Test
    fun testListApiKeysDelegatesToService() {
        val studyId = UUID.randomUUID()
        val keys = listOf(Mockito.mock(ApiKeyInfo::class.java))
        Mockito.`when`(apiKeyService.listApiKeys(studyId)).thenReturn(keys)

        val result = controller.listApiKeys(studyId)
        assertNotNull(result)
        assertEquals(1, result.size)
        verify(apiKeyService).listApiKeys(studyId)
    }

    @Test
    fun testListApiKeysReturnsEmptyList() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(apiKeyService.listApiKeys(studyId)).thenReturn(emptyList())

        val result = controller.listApiKeys(studyId)
        assertTrue(result.isEmpty())
    }

    @Test
    fun testListApiKeysReturnsMultipleKeys() {
        val studyId = UUID.randomUUID()
        val keys = listOf(
            Mockito.mock(ApiKeyInfo::class.java),
            Mockito.mock(ApiKeyInfo::class.java),
            Mockito.mock(ApiKeyInfo::class.java)
        )
        Mockito.`when`(apiKeyService.listApiKeys(studyId)).thenReturn(keys)

        val result = controller.listApiKeys(studyId)
        assertEquals(3, result.size)
    }

    @Test
    fun testListApiKeysPassesStudyId() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(apiKeyService.listApiKeys(studyId)).thenReturn(emptyList())

        controller.listApiKeys(studyId)
        verify(apiKeyService).listApiKeys(studyId)
    }

    @Test(expected = RuntimeException::class)
    fun testListApiKeysPropagatesServiceException() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(apiKeyService.listApiKeys(studyId)).thenThrow(RuntimeException("list failed"))

        controller.listApiKeys(studyId)
    }

    @Test
    fun testListApiKeysReturnsSameListFromService() {
        val studyId = UUID.randomUUID()
        val keys = listOf(Mockito.mock(ApiKeyInfo::class.java))
        Mockito.`when`(apiKeyService.listApiKeys(studyId)).thenReturn(keys)

        val result = controller.listApiKeys(studyId)
        assertSame(keys, result)
    }

    // --- revokeApiKey ---

    @Test
    fun testRevokeApiKeyDelegatesToService() {
        val studyId = UUID.randomUUID()
        val keyId = UUID.randomUUID()

        val result = controller.revokeApiKey(studyId, keyId)
        assertNotNull(result)
        assertEquals(OK.ok, result)
    }

    @Test
    fun testRevokeApiKeyReturnsOk() {
        val studyId = UUID.randomUUID()
        val keyId = UUID.randomUUID()

        val result = controller.revokeApiKey(studyId, keyId)
        assertSame(OK.ok, result)
    }

    @Test
    fun testRevokeApiKeyCallsServiceWithCorrectArgs() {
        val studyId = UUID.randomUUID()
        val keyId = UUID.randomUUID()

        controller.revokeApiKey(studyId, keyId)
        verify(apiKeyService).revokeApiKey(kEq(studyId), kEq(keyId), kAnyString())
    }

    @Test(expected = RuntimeException::class)
    fun testRevokeApiKeyPropagatesServiceException() {
        val studyId = UUID.randomUUID()
        val keyId = UUID.randomUUID()
        Mockito.doThrow(RuntimeException("revoke failed"))
            .`when`(apiKeyService).revokeApiKey(kEq(studyId), kEq(keyId), kAnyString())

        controller.revokeApiKey(studyId, keyId)
    }

    @Test
    fun testRevokeApiKeyMultipleKeys() {
        val studyId = UUID.randomUUID()
        val keyId1 = UUID.randomUUID()
        val keyId2 = UUID.randomUUID()

        assertEquals(OK.ok, controller.revokeApiKey(studyId, keyId1))
        assertEquals(OK.ok, controller.revokeApiKey(studyId, keyId2))
    }

    // --- rotateApiKey ---

    @Test
    fun testRotateApiKeyDelegatesToService() {
        val studyId = UUID.randomUUID()
        val keyId = UUID.randomUUID()
        val response = Mockito.mock(ApiKeyCreateResponse::class.java)
        Mockito.`when`(apiKeyService.rotateApiKey(kEq(studyId), kEq(keyId), kAnyString()))
            .thenReturn(response)

        val result = controller.rotateApiKey(studyId, keyId)
        assertNotNull(result)
    }

    @Test
    fun testRotateApiKeyReturnsServiceResponse() {
        val studyId = UUID.randomUUID()
        val keyId = UUID.randomUUID()
        val response = Mockito.mock(ApiKeyCreateResponse::class.java)
        Mockito.`when`(apiKeyService.rotateApiKey(kEq(studyId), kEq(keyId), kAnyString()))
            .thenReturn(response)

        val result = controller.rotateApiKey(studyId, keyId)
        assertSame(response, result)
    }

    @Test
    fun testRotateApiKeyPassesCorrectArgs() {
        val studyId = UUID.randomUUID()
        val keyId = UUID.randomUUID()
        val response = Mockito.mock(ApiKeyCreateResponse::class.java)
        Mockito.`when`(apiKeyService.rotateApiKey(kEq(studyId), kEq(keyId), kAnyString()))
            .thenReturn(response)

        controller.rotateApiKey(studyId, keyId)
        verify(apiKeyService).rotateApiKey(kEq(studyId), kEq(keyId), kAnyString())
    }

    @Test(expected = RuntimeException::class)
    fun testRotateApiKeyPropagatesServiceException() {
        val studyId = UUID.randomUUID()
        val keyId = UUID.randomUUID()
        Mockito.`when`(apiKeyService.rotateApiKey(kEq(studyId), kEq(keyId), kAnyString()))
            .thenThrow(RuntimeException("rotate failed"))

        controller.rotateApiKey(studyId, keyId)
    }

    @Test
    fun testRotateApiKeyForDifferentKeys() {
        val studyId = UUID.randomUUID()
        val keyId1 = UUID.randomUUID()
        val keyId2 = UUID.randomUUID()
        val response1 = Mockito.mock(ApiKeyCreateResponse::class.java)
        val response2 = Mockito.mock(ApiKeyCreateResponse::class.java)
        Mockito.`when`(apiKeyService.rotateApiKey(kEq(studyId), kEq(keyId1), kAnyString()))
            .thenReturn(response1)
        Mockito.`when`(apiKeyService.rotateApiKey(kEq(studyId), kEq(keyId2), kAnyString()))
            .thenReturn(response2)

        assertSame(response1, controller.rotateApiKey(studyId, keyId1))
        assertSame(response2, controller.rotateApiKey(studyId, keyId2))
    }

    @Test
    fun testServiceCalledOnceForCreate() {
        val studyId = UUID.randomUUID()
        val request = Mockito.mock(ApiKeyCreateRequest::class.java)
        val response = Mockito.mock(ApiKeyCreateResponse::class.java)
        Mockito.`when`(apiKeyService.createApiKey(kEq(studyId), kAnyString(), kEq(request)))
            .thenReturn(response)

        controller.createApiKey(studyId, request)
        verify(apiKeyService, Mockito.times(1)).createApiKey(kEq(studyId), kAnyString(), kEq(request))
    }

    @Test
    fun testServiceCalledOnceForList() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(apiKeyService.listApiKeys(studyId)).thenReturn(emptyList())

        controller.listApiKeys(studyId)
        verify(apiKeyService, Mockito.times(1)).listApiKeys(studyId)
    }
}
