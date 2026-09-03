package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitKeyStrategy
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.filters.MobileReviewerAuthenticationToken
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.services.enrollment.ReviewerEnrollmentBootstrapResponse
import com.openlattice.chronicle.services.enrollment.ReviewerEnrollmentService
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
public open class ReviewerEnrollmentController(
    private val reviewerEnrollmentService: ReviewerEnrollmentService,
) {
    public companion object {
        public const val PATH: String = "/chronicle/v4/mobile/reviewer-enrollment"
        public const val SERVLET_RELATIVE_PATH: String = "/v4/mobile/reviewer-enrollment"
    }

    @Timed
    @RateLimit(type = RateLimitType.AUTH, keyStrategy = RateLimitKeyStrategy.IP)
    @PostMapping(path = [PATH, SERVLET_RELATIVE_PATH])
    public fun createReviewerEnrollment(
        authentication: Authentication,
        response: HttpServletResponse,
    ): ReviewerEnrollmentBootstrapResponse {
        val reviewerAuthentication = authentication as? MobileReviewerAuthenticationToken
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, Messages.get("error.reviewer.credentialInvalid"))
        if (!reviewerEnrollmentService.isConfiguredStudy(reviewerAuthentication.studyId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, Messages.get("error.reviewer.scopeMismatch"))
        }
        val result = reviewerEnrollmentService.mint()
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        response.setHeader("Pragma", "no-cache")
        return result
    }
}
