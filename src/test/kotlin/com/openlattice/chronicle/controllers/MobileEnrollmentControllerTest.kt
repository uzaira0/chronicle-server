package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import com.openlattice.chronicle.services.delete.MobileSelfWithdrawalResult
import com.openlattice.chronicle.services.delete.ParticipantPurgeService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.util.UUID

class MobileEnrollmentControllerTest {
    private val participantPurgeService = Mockito.mock(ParticipantPurgeService::class.java)
    private val studyId = UUID.randomUUID()
    private val keyId = UUID.randomUUID()
    private val deviceId = UUID.randomUUID()
    private val participantId = "participant-1"
    private lateinit var controller: MobileEnrollmentController
    private lateinit var token: ApiKeyAuthenticationToken

    @Before
    fun setUp() {
        controller = MobileEnrollmentController(participantPurgeService)
        token = ApiKeyAuthenticationToken(
            principal = "apikey:$keyId",
            keyId = keyId,
            studyId = studyId,
            participantId = participantId,
            deviceId = deviceId,
            scope = ApiKeyScope.WRITE,
            authorities = listOf(SimpleGrantedAuthority("ROLE_API_KEY")),
        )
    }

    @Test
    fun firstWithdrawalStartsDeletionMarksEnrollmentAndRevokesCredential() {
        val deletionOperationId = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        Mockito.`when`(
            participantPurgeService.executeSelfWithdrawal(
                studyId,
                participantId,
                deviceId,
                keyId,
                requestId,
            ),
        ).thenReturn(
            MobileSelfWithdrawalResult(
                requestId = requestId,
                alreadyWithdrawn = false,
                deletionOperationId = deletionOperationId,
            ),
        )

        val response = controller.withdrawCurrentEnrollment(requestId.toString(), token)

        assertFalse(response.alreadyWithdrawn)
        assertEquals(listOf(deletionOperationId), response.deletionJobIds)
        Mockito.verify(participantPurgeService).executeSelfWithdrawal(
            studyId,
            participantId,
            deviceId,
            keyId,
            requestId,
        )
    }

    @Test
    fun repeatedWithdrawalReturnsStableRequestWithoutStartingAnotherDeletion() {
        val requestId = UUID.randomUUID()
        Mockito.`when`(
            participantPurgeService.executeSelfWithdrawal(
                studyId,
                participantId,
                deviceId,
                keyId,
                requestId,
            ),
        ).thenReturn(
            MobileSelfWithdrawalResult(
                requestId = requestId,
                alreadyWithdrawn = true,
                deletionOperationId = null,
            ),
        )

        val first = controller.withdrawCurrentEnrollment(requestId.toString(), token)
        val second = controller.withdrawCurrentEnrollment(requestId.toString(), token)

        assertTrue(first.alreadyWithdrawn)
        assertTrue(second.alreadyWithdrawn)
        assertEquals(first.requestId, second.requestId)
        assertTrue(first.deletionJobIds.isEmpty())
        assertTrue(second.deletionJobIds.isEmpty())
        Mockito.verify(participantPurgeService, Mockito.times(2)).executeSelfWithdrawal(
            studyId,
            participantId,
            deviceId,
            keyId,
            requestId,
        )
    }

    @Test
    fun replayUsesTheOriginallyStoredWithdrawalSnapshotAfterResponseLoss() {
        val requestId = UUID.randomUUID()
        val existingOperationId = UUID.randomUUID()
        Mockito.`when`(
            participantPurgeService.executeSelfWithdrawal(
                studyId,
                participantId,
                deviceId,
                keyId,
                requestId,
            ),
        ).thenReturn(
            MobileSelfWithdrawalResult(
                requestId = requestId,
                alreadyWithdrawn = false,
                deletionOperationId = existingOperationId,
            ),
        )

        val response = controller.withdrawCurrentEnrollment(requestId.toString(), token)

        assertFalse(response.alreadyWithdrawn)
        assertEquals(requestId, response.requestId)
        assertEquals(listOf(existingOperationId), response.deletionJobIds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonCanonicalWithdrawalRequestIdIsRejectedBeforeAnyDestructiveWork() {
        controller.withdrawCurrentEnrollment(UUID.randomUUID().toString().uppercase(), token)
    }
}
