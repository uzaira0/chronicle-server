package com.openlattice.chronicle.client

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any as kAny
import java.util.function.Supplier

class ResearcherCreateHeaderTest {
    @Test
    fun legacyTestClientsKeepCreatesKeylessAndPreserveExplicitRetryKeys() {
        val interceptor = RetrofitClientFactory.okHttpClientWithOpenLatticeAuth(Supplier { "fixture-jwt" })
            .build().interceptors.last()
        fun headers(path: String, key: String? = null, method: String = "POST"): Request {
            val request = Request.Builder().url("http://localhost$path")
                .method(method, if (method == "POST") "{}".toRequestBody() else null)
                .apply { if (key != null) header("Idempotency-Key", key) }.build()
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
            "/chronicle/v3/study/$study/export/async", "/chronicle/v3/study/$study/api-keys")) {
            assertNull("legacy fixture creates stay keyless: $path", headers(path).header("Idempotency-Key"))
            val key = "explicit-logical-create-key"
            repeat(2) {
                assertEquals("retry must keep the original intent", key, headers(path, key).header("Idempotency-Key"))
            }
        }
        assertNull(headers("/chronicle/v3/study", method = "GET").header("Idempotency-Key"))
        assertNull(headers("/chronicle/v3/study/$study/participant").header("Idempotency-Key"))
    }
}
