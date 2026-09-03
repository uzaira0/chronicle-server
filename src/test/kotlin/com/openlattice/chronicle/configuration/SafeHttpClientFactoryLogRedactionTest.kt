package com.openlattice.chronicle.configuration

import com.openlattice.chronicle.util.SsrfException
import okhttp3.Interceptor
import okhttp3.Request
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Appender
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.util.concurrent.CopyOnWriteArrayList

class SafeHttpClientFactoryLogRedactionTest {

    private val captured = CopyOnWriteArrayList<String>()
    private lateinit var appender: Appender
    private lateinit var coreLogger: Logger

    private class CapturingAppender(private val sink: MutableList<String>) :
        AbstractAppender("safe-http-url-redaction-capture", null, null, true, Property.EMPTY_ARRAY) {
        override fun append(event: LogEvent) {
            sink.add(event.message.formattedMessage)
        }
    }

    @Before
    fun attachAppender() {
        appender = CapturingAppender(captured).also { it.start() }
        val logger = LogManager.getRootLogger() as Logger
        logger.addAppender(appender)
        coreLogger = logger
    }

    @After
    fun detachAppender() {
        coreLogger.removeAppender(appender)
        appender.stop()
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
