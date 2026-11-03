package com.openlattice.chronicle.client

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any as kAny
import java.util.function.Supplier

class ResearcherCreateHeaderTest {
    @Test
    fun fixtureCreatesReceiveAnIntentKeyAndPreserveExplicitRetryKeys() {
        val interceptor = RetrofitClientFactory.okHttpClientWithOpenLatticeAuth(Supplier { "fixture-jwt" })
            .build().interceptors.last()
        fun headers(path: String, key: String? = null, method: String = "POST"): Request {
            val request = Request.Builder().url("http://localhost$path")
                .method(method, if (method == "POST") "{}".toRequestBody() else null)
                .apply { if (key != null) header(ParticipantFormAccessFilter.IDEMPOTENCY_HEADER, key) }.build()
            val chain = mock(Interceptor.Chain::class.java)
            `when`(chain.request()).thenReturn(request)
            var supplied: Request? = null
            `when`(chain.proceed(kAny())).thenAnswer { invocation ->
                supplied = invocation.getArgument(0)
                Response.Builder().request(supplied!!).protocol(Protocol.HTTP_1_1).code(201).message("Created").body("".toResponseBody()).build()
            }
            interceptor.intercept(chain).close()
            return supplied!!
        }
        val study = "11111111-1111-1111-1111-111111111111"
        for (path in listOf("/chronicle/v3/study", "/chronicle/v3/survey/$study/questionnaire",
            "/chronicle/v3/study/$study/api-keys",
            "/chronicle/v3/study/$study/webhooks", "/chronicle/v3/study/$study/clone",
            "/chronicle/v3/organization", "/chronicle/v3/candidate")) {
            assertNotNull(path, headers(path).header(ParticipantFormAccessFilter.IDEMPOTENCY_HEADER))
            val key = "explicit-logical-create-key"
            repeat(2) {
                assertEquals("retry must keep the original intent", key, headers(path, key).header(ParticipantFormAccessFilter.IDEMPOTENCY_HEADER))
            }
        }
        assertNull(headers("/chronicle/v3/study", method = "GET").header(ParticipantFormAccessFilter.IDEMPOTENCY_HEADER))
        assertNull(headers("/chronicle/v3/study/$study/participant").header(ParticipantFormAccessFilter.IDEMPOTENCY_HEADER))
    }
}
