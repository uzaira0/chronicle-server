package com.openlattice.chronicle.fuzz

import org.mockito.Mockito

/**
 * Shared constants and utilities for fuzz tests.
 */
object FuzzTestConstants {

    /**
     * Combined SQL injection payloads used across fuzz test suites.
     * Sourced from ParticipantIdValidatorFuzzTest and SqlIdentifierValidatorFuzzTest.
     */
    val SQL_INJECTION_PAYLOADS: List<String> = listOf(
        "' OR '1'='1",
        "'; DROP TABLE users; --",
        "'; DROP TABLE users;--",
        "1; SELECT * FROM",
        "' UNION SELECT",
        "admin'--",
        "admin' UNION SELECT * FROM passwords--",
        "\u0000",
        "\u0000; DELETE FROM",
        "Robert'); DROP TABLE Students;--",
        "1 OR 1=1",
        "1; EXEC xp_cmdshell('cmd')",
        "table_name\"; DROP TABLE important;--"
    )

    /**
     * Creates a mock [jakarta.servlet.http.HttpServletRequest] with the given server properties.
     */
    fun mockHttpServletRequest(
        serverName: String = "app.example.com",
        serverPort: Int = 443,
        scheme: String = "https"
    ): jakarta.servlet.http.HttpServletRequest {
        val request = Mockito.mock(jakarta.servlet.http.HttpServletRequest::class.java)
        Mockito.`when`(request.serverName).thenReturn(serverName)
        Mockito.`when`(request.serverPort).thenReturn(serverPort)
        Mockito.`when`(request.scheme).thenReturn(scheme)
        return request
    }
}
