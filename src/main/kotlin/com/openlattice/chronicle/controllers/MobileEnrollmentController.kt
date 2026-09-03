package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.filters.ApiKeyAuthenticationFilter
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.services.apikeys.InvalidWithdrawalRequestException
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

@RestController
@RequestMapping(path = ["/chronicle/v4/mobile/enrollments", "/v4/mobile/enrollments"])
@Timed
@RateLimit(type = RateLimitType.SENSITIVE)
public open class MobileEnrollmentController(
    private val participantPurgeService: ParticipantPurgeService,
) {
    @DeleteMapping("/current")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public fun withdrawCurrentEnrollment(
        @RequestHeader(ApiKeyAuthenticationFilter.WITHDRAWAL_REQUEST_ID_HEADER) withdrawalRequestId: String,
        authentication: Authentication,
    ): EnrollmentWithdrawalResponse {
        val token = authentication as? ApiKeyAuthenticationToken
            ?: throw IllegalStateException("A mobile API key is required")
        val participantId = requireNotNull(token.participantId) { "API key is not participant-bound" }
        val deviceId = requireNotNull(token.deviceId) { "API key is not device-bound" }
        val requestId = requireNotNull(MobileWithdrawalRequestIds.parse(withdrawalRequestId)) {
            "Withdrawal request id must be a canonical lowercase UUID"
        }
        val withdrawal = try {
            participantPurgeService.executeSelfWithdrawal(
                token.studyId,
                participantId,
                deviceId,
                token.keyId,
                requestId,
            )
        } catch (_: InvalidWithdrawalRequestException) {
            throw org.springframework.web.server.ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                Messages.get("error.enrollment.apiKeyInvalid"),
            )
        }

        return EnrollmentWithdrawalResponse(
            requestId = withdrawal.requestId,
            deletionJobIds = listOfNotNull(withdrawal.deletionOperationId),
            alreadyWithdrawn = withdrawal.alreadyWithdrawn,
        )
    }
}
