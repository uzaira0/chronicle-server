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

import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.authorization.JwtBlocklist
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.testing.isolatedHazelcastConfig
import org.junit.AfterClass
import org.junit.BeforeClass
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerExecutionChain
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

abstract class RateLimitFixture {
    companion object {
        private lateinit var sharedHazelcast: HazelcastInstance

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val config = isolatedHazelcastConfig("rate-limit-test")
            sharedHazelcast = Hazelcast.newHazelcastInstance(config)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            sharedHazelcast.shutdown()
        }
    }

    /** Every request resolves to [handler], one of the endpoint shapes below. */
    protected fun createFilter(
        config: RateLimitConfiguration = RateLimitConfiguration(),
        handler: String = "loginHandler",
    ): RateLimitFilter {
        val mapping = mock<RequestMappingHandlerMapping>()
        whenever(mapping.getHandler(any()))
            .thenReturn(HandlerExecutionChain(HandlerMethod(this, RateLimitFixture::class.java.getMethod(handler))))
        return RateLimitFilter(hazelcastInstance = hz, config = config, handlerMapping = mapping)
    }

    @RateLimit(type = RateLimitType.AUTH)
    fun loginHandler() = Unit

    @RateLimit(type = RateLimitType.WRITE, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    fun enrollHandler() = Unit

    @RateLimit(type = RateLimitType.READ, keyStrategy = RateLimitKeyStrategy.PARTICIPANT_STUDY)
    fun previewHandler() = Unit

    fun authenticatedHandler() = Unit

    protected fun createRequest(
        path: String = "/chronicle/v3/study/550e8400-e29b-41d4-a716-446655440000/participants",
        method: String = "GET",
        remoteAddr: String = "203.0.113.42"
    ): MockHttpServletRequest {
        val request = MockHttpServletRequest(method, path)
        request.remoteAddr = remoteAddr
        return request
    }

    protected val hz: HazelcastInstance get() = sharedHazelcast

    protected fun createPreAuthenticationFilter(
        config: RateLimitConfiguration,
        apiKeys: ApiKeyService = mock(),
        participantForms: ParticipantFormAccessService = mock(),
        jwtDecoder: JwtDecoder = mock(),
        jwtBlocklist: JwtBlocklist = mock(),
    ): PreAuthenticationRateLimitFilter =
        PreAuthenticationRateLimitFilter(hz, config, jwtDecoder, jwtBlocklist, ChronicleAuthConfiguration(), apiKeys, participantForms)
}
