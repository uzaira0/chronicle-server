package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.filters.ApiKeyAuthenticationFilter
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.services.apikeys.InvalidWithdrawalRequestException
import com.openlattice.chronicle.services.apikeys.PendingAttemptCancellation
import com.openlattice.chronicle.services.apikeys.MobileWithdrawalRequestIds
import com.openlattice.chronicle.services.delete.ParticipantPurgeService
import com.openlattice.chronicle.study.EnrollmentWithdrawalResponse
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping(path = ["/chronicle/v4/mobile/enrollments", "/v4/mobile/enrollments"])
@Timed
public open class MobileEnrollmentController(
    private val participantPurgeService: ParticipantPurgeService,
    private val apiKeyService: ApiKeyService,
) {
    @DeleteMapping("/current")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public fun withdrawCurrentEnrollment(
        @RequestHeader(ApiKeyAuthenticationFilter.WITHDRAWAL_REQUEST_ID_HEADER) withdrawalRequestId: String,
        authentication: Authentication,
    ): EnrollmentWithdrawalResponse {
        val token = authentication as? ApiKeyAuthenticationToken
            ?: error("A mobile API key is required")
        val requestId = requireNotNull(MobileWithdrawalRequestIds.parse(withdrawalRequestId)) {
            "Withdrawal request id must be a canonical lowercase UUID"
        }
        // Erasure already deleted this key's participant: acknowledge so the phone finishes its local erasure.
        if (token.erased) {
            return EnrollmentWithdrawalResponse(requestId = requestId, deletionJobIds = emptyList(), alreadyWithdrawn = true)
        }
        val participantId = requireNotNull(token.participantId) { "API key is not participant-bound" }
        val randomDeviceId = requireNotNull(token.randomDeviceId) { "API key is not device-bound" }
        // Cancelling an attempt revokes its binding and key, never withdraws; the attempt id is the receipt.
        token.pendingAttemptKeyHash?.let { keyHash ->
            when (apiKeyService.cancelPendingEnrollmentAttempt(token.studyId, participantId, randomDeviceId, requestId, keyHash)) {
                PendingAttemptCancellation.UNKNOWN ->
                    throw ResponseStatusException(HttpStatus.GONE, Messages.get("error.enrollment.apiKeyInvalid"))
                PendingAttemptCancellation.NOT_CANCELLABLE ->
                    throw ResponseStatusException(HttpStatus.CONFLICT, Messages.get("error.enrollment.cancelAfterEnrollment"))
                PendingAttemptCancellation.CANCELLED, PendingAttemptCancellation.ALREADY_CANCELLED ->
                    return EnrollmentWithdrawalResponse(requestId = requestId)
            }
        }
        val withdrawal = try {
            participantPurgeService.executeSelfWithdrawal(
                token.studyId,
                participantId,
                randomDeviceId,
                token.keyId,
                requestId,
            )
        } catch (_: InvalidWithdrawalRequestException) {
            // Permanent for this key and request id: 410 routes the phone to support instead of retrying.
            throw ResponseStatusException(HttpStatus.GONE, Messages.get("error.enrollment.apiKeyInvalid"))
        }

        return EnrollmentWithdrawalResponse(
            requestId = withdrawal.requestId,
            deletionJobIds = listOfNotNull(withdrawal.deletionOperationId),
            alreadyWithdrawn = withdrawal.alreadyWithdrawn,
        )
    }
}
