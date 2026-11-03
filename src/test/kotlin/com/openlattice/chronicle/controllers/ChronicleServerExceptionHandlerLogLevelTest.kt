package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.ScopedLogCapture
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.configuration.ErrorSanitizationConfig
import org.apache.logging.log4j.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.server.ResponseStatusException

/** Client mistakes log below ERROR, so the ERROR stream holds only server faults. */
class ChronicleServerExceptionHandlerLogLevelTest {
    private val handler = ChronicleServerExceptionHandler(
        Mockito.mock(AuditingManager::class.java),
        ErrorSanitizationConfig(sanitizeErrors = true, includeErrorId = true, logFullErrors = true),
    )
    private val request = MockHttpServletRequest("GET", "/chronicle/v3/study")

    @Test
    fun clientErrorsStayOutOfTheErrorLog() {
        ScopedLogCapture(ChronicleServerExceptionHandler::class.java, Level.ERROR).use { errors ->
            handler.handleResponseStatusException(request, ResponseStatusException(HttpStatus.NOT_FOUND))
            handler.handleIllegalArgumentException(request, IllegalArgumentException("bad input"))
            handler.handleUnauthorizedExceptions(request, AccessDeniedException("denied"))
            assertEquals(emptyList<String>(), errors.messages.toList())
        }
    }

    @Test
    fun serverFaultsStillLogAtError() {
        ScopedLogCapture(ChronicleServerExceptionHandler::class.java, Level.ERROR).use { errors ->
            handler.handleResponseStatusException(request, ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE))
            assertTrue(errors.messages.isNotEmpty())
        }
        ScopedLogCapture(ChronicleServerExceptionHandler::class.java, Level.ERROR).use { errors ->
            handler.handleOtherExceptions(request, RuntimeException("boom"))
            assertTrue(errors.messages.isNotEmpty())
        }
    }
}
