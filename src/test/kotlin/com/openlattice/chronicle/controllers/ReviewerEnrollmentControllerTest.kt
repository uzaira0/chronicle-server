package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.filters.MobileReviewerAuthenticationToken
import com.openlattice.chronicle.services.enrollment.ReviewerEnrollmentBootstrapResponse
import com.openlattice.chronicle.services.enrollment.ReviewerEnrollmentService
import com.openlattice.chronicle.study.EnrollmentPreviewResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

class ReviewerEnrollmentControllerTest {
    private val service = Mockito.mock(ReviewerEnrollmentService::class.java)
    private val controller = ReviewerEnrollmentController(service)
    private val studyId = UUID.fromString("00000000-0000-0000-0000-000000000401")

    @Test
    fun `bootstrap route is exact POST and successful responses cannot be cached`() {
        val responseBody = ReviewerEnrollmentBootstrapResponse(
            "a".repeat(64),
            Mockito.mock(EnrollmentPreviewResponse::class.java),
        )
        whenever(service.isConfiguredStudy(studyId)).thenReturn(true)
        whenever(service.mint()).thenReturn(responseBody)
        val response = MockHttpServletResponse()

        val actual = controller.createReviewerEnrollment(
            MobileReviewerAuthenticationToken(studyId),
            response,
        )

        assertEquals(responseBody, actual)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals("no-cache", response.getHeader("Pragma"))
        val mapping = ReviewerEnrollmentController::class.java
            .getMethod(
                "createReviewerEnrollment",
                org.springframework.security.core.Authentication::class.java,
                jakarta.servlet.http.HttpServletResponse::class.java,
            )
            .getAnnotation(PostMapping::class.java)
        assertEquals(
            setOf(
                ReviewerEnrollmentController.PATH,
                ReviewerEnrollmentController.SERVLET_RELATIVE_PATH,
            ),
            (mapping.path + mapping.value).toSet(),
        )
    }

    @Test
    fun `anonymous or differently scoped authentication cannot invoke reviewer minting`() {
        val anonymous = AnonymousAuthenticationToken(
            "key",
            "anonymous",
            AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"),
        )
        val anonymousFailure = assertThrows(ResponseStatusException::class.java) {
            controller.createReviewerEnrollment(anonymous, MockHttpServletResponse())
        }
        assertEquals(401, anonymousFailure.statusCode.value())

        val otherStudyFailure = assertThrows(ResponseStatusException::class.java) {
            controller.createReviewerEnrollment(
                MobileReviewerAuthenticationToken(UUID.randomUUID()),
                MockHttpServletResponse(),
            )
        }
        assertEquals(403, otherStudyFailure.statusCode.value())
        verify(service, never()).mint()
    }
}
