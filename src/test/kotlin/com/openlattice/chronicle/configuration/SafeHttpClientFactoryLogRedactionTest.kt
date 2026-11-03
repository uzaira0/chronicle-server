package com.openlattice.chronicle.configuration

import com.openlattice.chronicle.ScopedLogCapture
import com.openlattice.chronicle.util.SsrfException
import okhttp3.Interceptor
import okhttp3.Request
import org.apache.logging.log4j.LogManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class SafeHttpClientFactoryLogRedactionTest {

    private lateinit var capture: ScopedLogCapture
    private val captured get() = capture.messages

    @Before
    fun attachAppender() {
        capture = ScopedLogCapture(LogManager.ROOT_LOGGER_NAME, pattern = "%m")
    }

    @After
    fun detachAppender() {
        capture.close()
    }

    @Test
    fun blockedRequestLogsStableTargetReferenceNeverUrlComponents() {
        val username = "researcher@example.org"
        val password = "url-password-secret"
        val privatePath = "participant-jane-doe"
        val querySecret = "one-time-webhook-token"
        val request = Request.Builder()
            .url("https://$username:$password@blocked.example/$privatePath?access_token=$querySecret")
            .build()
        val chain = Mockito.mock(Interceptor.Chain::class.java)
        Mockito.`when`(chain.request()).thenReturn(request)

        assertThrows(SsrfException::class.java) {
            SafeHttpClientFactory.SsrfInterceptor(SsrfConfig()).intercept(chain)
        }

        val log = captured.joinToString("\n")
        assertTrue("expected a stable target reference in log, got: $log", log.contains("url:"))
        assertTrue("expected structural violation type in log, got: $log", log.contains("DISALLOWED_HOST"))
        listOf(username, password, "blocked.example", privatePath, querySecret).forEach { secret ->
            assertFalse("outbound URL component leaked into logs: $secret in $log", log.contains(secret))
        }
    }
}
