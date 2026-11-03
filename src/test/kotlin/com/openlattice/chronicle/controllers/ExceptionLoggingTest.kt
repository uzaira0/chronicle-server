package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.ScopedLogCapture
import com.openlattice.chronicle.configuration.ErrorSanitizationConfig
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpInputMessage
import org.springframework.http.converter.HttpMessageNotReadableException

class ExceptionLoggingTest {
    private val logging = ExceptionLogging(ErrorSanitizationConfig())

    @Test
    fun unreadableBodyMeasurementDoesNotSwallowErrors() {
        val failure = AssertionError("body-error-sentinel")
        val thrown = assertThrows(AssertionError::class.java) {
            logging.logUnreadableBody("error-reference", unreadableBody(failure))
        }
        assertTrue(thrown === failure)
    }

    @Test
    fun unreadableBodyMeasurementRecoversFromExceptionsWithoutLoggingContent() {
        ScopedLogCapture(ChronicleServerExceptionHandler::class.java).use { capture ->
            logging.logUnreadableBody("error-reference", unreadableBody(IOException("body-private-sentinel")))
            assertTrue(capture.messages.any { it.contains("length=-1") })
            assertFalse(capture.messages.any { it.contains("body-private-sentinel") })
        }
    }

    private fun unreadableBody(failure: Throwable) = HttpMessageNotReadableException(
        "Unreadable body",
        object : HttpInputMessage {
            override fun getBody(): InputStream = throw failure
            override fun getHeaders() = HttpHeaders()
        },
    )
}
