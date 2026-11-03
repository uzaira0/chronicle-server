package com.openlattice.chronicle.controllers

import org.junit.Assert.assertThrows

import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.services.apikeys.InvalidWithdrawalRequestException
import com.openlattice.chronicle.services.apikeys.PendingAttemptCancellation
import com.openlattice.chronicle.services.delete.MobileSelfWithdrawalResult
import com.openlattice.chronicle.services.delete.ParticipantPurgeService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

class MobileEnrollmentControllerTest {
    private val participantPurgeService = Mockito.mock(ParticipantPurgeService::class.java)
    private val studyId = UUID.randomUUID()
    private val keyId = UUID.randomUUID()
    private val randomDeviceId = UUID.randomUUID()
    private val participantId = "participant-1"
    private lateinit var controller: MobileEnrollmentController
    private lateinit var token: ApiKeyAuthenticationToken

    @Before
    fun setUp() {
        controller = MobileEnrollmentController(participantPurgeService, Mockito.mock(ApiKeyService::class.java))
        token = ApiKeyAuthenticationToken(
            principal = "apikey:$keyId",
            keyId = keyId,
            studyId = studyId,
            participantId = participantId,
            randomDeviceId = randomDeviceId,
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
                randomDeviceId,
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
            randomDeviceId,
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
                randomDeviceId,
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
            randomDeviceId,
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
                randomDeviceId,
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

    @Test
    fun keyDeletedByErasureIsAcknowledgedAsAlreadyWithdrawnWithoutDeletionWork() {
        val requestId = UUID.randomUUID()

        val response = controller.withdrawCurrentEnrollment(requestId.toString(), ApiKeyAuthenticationToken.erased())

        assertTrue(response.alreadyWithdrawn)
        assertEquals(requestId, response.requestId)
        assertEquals(emptyList<UUID>(), response.deletionJobIds)
        Mockito.verifyNoInteractions(participantPurgeService)
    }

    /** 410 tells the phone this withdrawal can never succeed with its credential; 401 stays retryable. */
    @Test
    fun permanentlyRefusedWithdrawalIsGoneSoThePhoneRoutesToSupport() {
        val requestId = UUID.randomUUID()
        Mockito.`when`(participantPurgeService.executeSelfWithdrawal(studyId, participantId, randomDeviceId, keyId, requestId))
            .thenThrow(InvalidWithdrawalRequestException())

        val refusal = assertThrows(ResponseStatusException::class.java) {
            controller.withdrawCurrentEnrollment(requestId.toString(), token)
        }
        assertEquals(HttpStatus.GONE, refusal.statusCode)
    }

    /**
     * A phone whose enrollment attempt bound but whose key never installed cancels that attempt: cancelled answers
     * 202 with the attempt id as receipt, a finished enrollment is a conflict telling it to withdraw instead, and an
     * attempt the server does not know is gone. None of these withdraws or deletes anything.
     */
    @Test
    fun cancellingABoundAttemptAnswersByOutcomeAndNeverWithdraws() {
        val apiKeys = Mockito.mock(ApiKeyService::class.java)
        val cancelling = MobileEnrollmentController(participantPurgeService, apiKeys)
        val attemptId = UUID.randomUUID()
        val pending = ApiKeyAuthenticationToken(
            principal = "apikey:$keyId", keyId = keyId, studyId = studyId, participantId = participantId,
            randomDeviceId = randomDeviceId, scope = ApiKeyScope.WRITE,
            authorities = listOf(SimpleGrantedAuthority("ROLE_API_KEY")),
            restriction = ApiKeyAuthenticationToken.Restriction.PendingAttempt("proposed-key-hash"),
        )
        fun cancel(outcome: PendingAttemptCancellation): Any {
            Mockito.`when`(apiKeys.cancelPendingEnrollmentAttempt(studyId, participantId, randomDeviceId, attemptId,
                "proposed-key-hash")).thenReturn(outcome)
            return try {
                cancelling.withdrawCurrentEnrollment(attemptId.toString(), pending)
            } catch (refused: ResponseStatusException) {
                refused.statusCode to refused.reason
            }
        }
        for (outcome in listOf(PendingAttemptCancellation.CANCELLED, PendingAttemptCancellation.ALREADY_CANCELLED)) {
            val receipt = cancel(outcome) as com.openlattice.chronicle.study.EnrollmentWithdrawalResponse
            assertEquals(attemptId, receipt.requestId)
            assertFalse(receipt.alreadyWithdrawn)
            assertTrue(receipt.deletionJobIds.isEmpty())
        }
        assertEquals(HttpStatus.CONFLICT to Messages.get("error.enrollment.cancelAfterEnrollment"),
            cancel(PendingAttemptCancellation.NOT_CANCELLABLE))
        assertEquals(HttpStatus.GONE to Messages.get("error.enrollment.apiKeyInvalid"),
            cancel(PendingAttemptCancellation.UNKNOWN))
        Mockito.verifyNoInteractions(participantPurgeService)
    }

    @Test
    fun nonCanonicalWithdrawalRequestIdIsRejectedBeforeAnyDestructiveWork() {
        assertThrows(IllegalArgumentException::class.java) {
            controller.withdrawCurrentEnrollment(UUID.randomUUID().toString().uppercase(), token)
        }
    }
}
