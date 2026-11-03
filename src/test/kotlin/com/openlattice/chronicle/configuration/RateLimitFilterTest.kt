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
package com.openlattice.chronicle.configuration

import io.github.bucket4j.TimeMeter
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerExecutionChain
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class RateLimitFilterTest : RateLimitFixture() {
    private fun uniqueIp() = "198.51.100.${(Math.random() * 254).toInt() + 1}"

    private fun singleRequestConfig(name: String) = RateLimitConfiguration(
        authRequestsPerMinute = 1,
        burstCapacityMultiplier = 1.0,
        hazelcastMapName = "${name}_${UUID.randomUUID()}",
    )

    @Test
    fun arbitraryPathsShareOneAnnotationCacheEntryEvenAfterRateLimitRejection() {
        val handler = spy(HandlerMethod(this, RateLimitFixture::class.java.getMethod("loginHandler")))
        val mapping = mock<RequestMappingHandlerMapping>()
        whenever(mapping.getHandler(any())).thenReturn(HandlerExecutionChain(handler))
        val filter = RateLimitFilter(hz, singleRequestConfig("RL_CACHE"), mapping)
        var rejected = false
        repeat(10_000) { index ->
            val response = MockHttpServletResponse()
            filter.doFilter(createRequest(path = "/chronicle/v4/arbitrary-$index/device-$index"), response, MockFilterChain())
            rejected = rejected || response.status == 429
        }
        verify(handler, times(1)).getMethodAnnotation(RateLimit::class.java)
        assertTrue(rejected)
    }

    @Test
    fun testRequestsExceedingLimitReturn429() {
        val limit = 3L
        val filter = createFilter(RateLimitConfiguration(
            authRequestsPerMinute = limit,
            burstCapacityMultiplier = 1.0,
            hazelcastMapName = "RL_TEST_429_${UUID.randomUUID()}",
        ))
        val ip = uniqueIp()
        for (i in 1..limit) {
            val res = MockHttpServletResponse()
            filter.doFilter(createRequest(remoteAddr = ip), res, MockFilterChain())
            assertEquals("Request $i should pass", 200, res.status)
        }
        val res = MockHttpServletResponse()
        filter.doFilter(createRequest(remoteAddr = ip), res, MockFilterChain())
        assertEquals(429, res.status)
    }

    @Test
    fun testRateLimitedResponseCarriesRetryAfterAndBody() {
        val filter = createFilter(singleRequestConfig("RL_TEST_BODY"))
        val ip = uniqueIp()
        filter.doFilter(createRequest(remoteAddr = ip), MockHttpServletResponse(), MockFilterChain())

        val res = MockHttpServletResponse()
        filter.doFilter(createRequest(remoteAddr = ip), res, MockFilterChain())

        assertEquals(429, res.status)
        assertEquals("application/json", res.contentType)
        val retryAfter = requireNotNull(res.getHeader("Retry-After")) { "Retry-After header should be set" }.toLong()
        assertTrue("Retry-After should be positive", retryAfter > 0)
        assertTrue(res.contentAsString.contains("Rate limit exceeded"))
    }

    /**
     * A participant re-opening the enrollment QR preview spends the read quota of that participant on that study,
     * not the enrollment-write quota, and another participant scanning from the same clinic network is unaffected.
     */
    @Test
    fun enrollmentPreviewSpendsTheReadQuotaOfItsOwnParticipant() {
        val filter = createFilter(RateLimitConfiguration(
            readRequestsPerMinute = 2,
            writeRequestsPerMinute = 1,
            burstCapacityMultiplier = 1.0,
            hazelcastMapName = "RL_PREVIEW_${UUID.randomUUID()}",
        ), handler = "previewHandler")
        val ip = uniqueIp()
        val study = UUID.randomUUID()
        fun preview(participant: String): Int {
            val response = MockHttpServletResponse()
            filter.doFilter(createRequest(path = "/chronicle/v4/study/$study/participant/$participant/enrollment-preview",
                remoteAddr = ip), response, MockFilterChain())
            return response.status
        }
        assertEquals(listOf(200, 200, 429), List(3) { preview("p-001") })
        assertEquals(200, preview("p-002"))
    }

    /**
     * A PUT to a GET-only route makes the handler lookup itself refuse the request. The limiter does not guess a tier
     * for it: the request goes on unlimited so Spring answers its own 405, instead of the limiter answering 500 or 429.
     */
    @Test
    fun aRequestNoHandlerAcceptsIsLeftToSpringInsteadOfBeingLimited() {
        val mapping = mock<RequestMappingHandlerMapping>()
        whenever(mapping.getHandler(any())).thenThrow(HttpRequestMethodNotSupportedException("PUT"))
        val filter = RateLimitFilter(hz, singleRequestConfig("RL_NO_HANDLER"), mapping)
        val ip = uniqueIp()
        val statuses = List(3) {
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()
            filter.doFilter(createRequest(method = "PUT", remoteAddr = ip), response, chain)
            response.status to (chain.request != null)
        }
        assertEquals(List(3) { 200 to true }, statuses)
    }

    /** An operator's whitelist typo (a /33 mask) is ignored on its own; the valid entries still bypass the limit. */
    @Test
    fun malformedWhitelistRangeIsIgnoredWhileValidEntriesStillBypass() {
        val filter = createFilter(singleRequestConfig("RL_BAD_CIDR").copy(
            whitelistedIps = listOf("203.0.113.0/33", "192.0.2.0/24"),
        ))
        fun statuses(ip: String) = List(3) {
            val response = MockHttpServletResponse()
            filter.doFilter(createRequest(remoteAddr = ip), response, MockFilterChain())
            response.status
        }
        assertEquals(listOf(200, 429, 429), statuses("203.0.113.7"))
        assertEquals(listOf(200, 200, 200), statuses("192.0.2.9"))
    }

    /** The two filters exactly as the server wires them from the operator's rate-limit settings. */
    private fun wiredFilters(
        config: RateLimitConfiguration,
        clock: TimeMeter? = null,
    ): Pair<jakarta.servlet.Filter, jakarta.servlet.Filter> =
        org.springframework.context.annotation.AnnotationConfigApplicationContext().use { context ->
            context.registerBean(com.hazelcast.core.HazelcastInstance::class.java, java.util.function.Supplier { hz })
            context.registerBean(RateLimitConfiguration::class.java, java.util.function.Supplier { config })
            clock?.let {
                context.registerBean("preAuthenticationRateLimitClock", TimeMeter::class.java,
                    java.util.function.Supplier { it })
            }
            context.registerBean(org.springframework.security.oauth2.jwt.JwtDecoder::class.java,
                java.util.function.Supplier { org.mockito.kotlin.mock<org.springframework.security.oauth2.jwt.JwtDecoder>() })
            context.registerBean(com.openlattice.chronicle.authorization.JwtBlocklist::class.java,
                java.util.function.Supplier { org.mockito.kotlin.mock<com.openlattice.chronicle.authorization.JwtBlocklist>() })
            context.registerBean(ChronicleAuthConfiguration::class.java, java.util.function.Supplier { ChronicleAuthConfiguration() })
            context.registerBean(com.openlattice.chronicle.services.apikeys.ApiKeyService::class.java,
                java.util.function.Supplier { org.mockito.kotlin.mock<com.openlattice.chronicle.services.apikeys.ApiKeyService>() })
            context.registerBean(com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService::class.java,
                java.util.function.Supplier {
                    org.mockito.kotlin.mock<com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService>()
                })
            context.register(RateLimitConfig::class.java)
            context.refresh()
            context.getBean("rateLimitFilter", jakarta.servlet.Filter::class.java) to
                context.getBean(PreAuthenticationRateLimitFilter::class.java)
        }

    /** The optional Spring collaborator reaches the real distributed pre-authentication bucket. */
    @Test
    fun configuredPreAuthenticationClockControlsRealQuotaRefill() {
        val now = AtomicLong(0L)
        val clock = object : TimeMeter {
            override fun currentTimeNanos(): Long = now.get()
            override fun isWallClockBased(): Boolean = true
        }
        val (_, preAuthentication) = wiredFilters(singleRequestConfig("RL_WIRED_CLOCK"), clock)
        val ip = uniqueIp()
        val refusesCredential = object : HttpServlet() {
            override fun service(request: HttpServletRequest, response: HttpServletResponse) {
                response.status = 401
            }
        }
        fun attempt(): Int {
            val response = MockHttpServletResponse()
            preAuthentication.doFilter(createRequest(remoteAddr = ip), response, MockFilterChain(refusesCredential))
            return response.status
        }
        assertEquals(listOf(401, 429), List(2) { attempt() })
        now.addAndGet(java.time.Duration.ofMinutes(1).toNanos())
        assertEquals("The injected clock must refill the same real quota", 401, attempt())
    }

    /** An operator who sets `enabled: false` gets no limit anywhere: not on login, not before authentication. */
    @Test
    fun disablingRateLimitsInTheOperatorSettingsLimitsNothing() {
        val config = singleRequestConfig("RL_WIRED_OFF").copy(enabled = false)
        val (endpointLimits, preAuthentication) = wiredFilters(config)
        val ip = uniqueIp()
        for (filter in listOf(endpointLimits, preAuthentication)) {
            repeat(3) {
                val response = MockHttpServletResponse()
                val chain = MockFilterChain()
                filter.doFilter(createRequest(remoteAddr = ip), response, chain)
                assertEquals(200, response.status)
                assertTrue(chain.request != null)
            }
        }
        // Failed credentials are what the pre-authentication quota counts; with limits off they are never refused.
        val refusesCredential = object : HttpServlet() {
            override fun service(request: HttpServletRequest, response: HttpServletResponse) {
                response.status = 401
            }
        }
        val failedLogins = List(3) {
            val response = MockHttpServletResponse()
            preAuthentication.doFilter(createRequest(remoteAddr = ip), response, MockFilterChain(refusesCredential))
            response.status
        }
        assertEquals(listOf(401, 401, 401), failedLogins)
    }

    /**
     * A second start on the same cluster with a different bucket lifetime keeps the cluster's existing bucket map
     * settings instead of failing startup, and pre-authentication limiting still applies.
     */
    @Test
    fun conflictingBucketMapSettingsKeepTheExistingMapAndStillLimit() {
        val config = singleRequestConfig("RL_WIRED_CONFLICT")
        wiredFilters(config)
        val (_, preAuthentication) = wiredFilters(config.copy(entryTtlSeconds = config.entryTtlSeconds + 60))
        assertEquals(config.entryTtlSeconds.toInt(), hz.config.getMapConfig(config.hazelcastMapName).timeToLiveSeconds)
        val ip = uniqueIp()
        val refusesCredential = object : HttpServlet() {
            override fun service(request: HttpServletRequest, response: HttpServletResponse) {
                response.status = 401
            }
        }
        // Only failed credential attempts spend the pre-authentication quota.
        val statuses = List(2) {
            val response = MockHttpServletResponse()
            preAuthentication.doFilter(createRequest(remoteAddr = ip), response, MockFilterChain(refusesCredential))
            response.status
        }
        assertEquals(listOf(401, 429), statuses)
    }

    @Test
    fun testDisabledConfigBypassesAllLimiting() {
        val filter = createFilter(singleRequestConfig("RL_TEST_DISABLED").copy(enabled = false))
        val ip = uniqueIp()
        for (i in 1..10) {
            val res = MockHttpServletResponse()
            filter.doFilter(createRequest(remoteAddr = ip), res, MockFilterChain())
            assertEquals("Disabled filter request $i should pass", 200, res.status)
        }
    }

    @Test
    fun onlyPreAuthenticationEndpointsCarryRateLimits() {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AnnotationTypeFilter(RestController::class.java))
        val limited = scanner.findCandidateComponents("com.openlattice.chronicle.controllers")
            .map { Class.forName(it.beanClassName) }
            .onEach { assertTrue("${it.simpleName} has a class-level limit", it.getAnnotation(RateLimit::class.java) == null) }
            .flatMap { controller ->
                controller.declaredMethods.filter { it.isAnnotationPresent(RateLimit::class.java) }
                    .map { "${controller.simpleName}.${it.name}" }
            }
            .toSortedSet()
        assertEquals(
            sortedSetOf(
                "AuthTokenController.dashboardLogin", "AuthTokenController.logout", "AuthTokenController.oidcCallback",
                "AuthTokenController.oidcLogin", "AuthTokenController.getSession", "AuthTokenController.setAuthCookie",
                "AuthTokenController.testingLogin", "ParticipantFormAccessController.exchangeAccessCode",
                "ParticipantFormAccessController.recoverSession", "ReviewerEnrollmentController.createReviewerEnrollment",
                "StudyV4Controller.enrollV4", "StudyV4Controller.getEnrollmentPreviewV4",
            ),
            limited,
        )
    }
}
