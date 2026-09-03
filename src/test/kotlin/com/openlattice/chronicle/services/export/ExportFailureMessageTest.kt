package com.openlattice.chronicle.services.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Export failure causes can contain infrastructure details and must never cross the study-readable
 * `ExportJobInfo.errorMessage` boundary.
 */
class ExportFailureMessageTest {

    @Test
    fun `the root cause is replaced by a safe category and opaque reference`() {
        val failure = RuntimeException(
            "wrapper",
            NoSuchElementException("Key platform_read is missing in the map"),
        )
        val message = ExportService.withFailureCause("Export generation failed; retry scheduled", failure)

        assertTrue(message.matches(Regex(
            "Export generation failed; retry scheduled " +
                "\\(category=internal; reference=[0-9a-f-]{36}\\)",
        )))
        assertFalse(message.contains("platform_read"))
        assertFalse(message.contains("NoSuchElementException"))
    }

    @Test
    fun `a cause with no message still exposes only its safe category`() {
        val message = ExportService.withFailureCause("Export request is invalid", IllegalArgumentException())

        assertTrue(message.contains("category=invalid-request"))
        assertFalse(message.contains("IllegalArgumentException"))
    }

    @Test
    fun `unavailable storage capacity exposes only the capacity category`() {
        val message = ExportService.withFailureCause(
            "Export resource limit exceeded",
            ExportCapacityUnavailableException(),
        )

        assertTrue(message.contains("category=capacity"))
        assertFalse(message.contains(ExportCapacityUnavailableException.MESSAGE))
        assertFalse(message.contains("ExportCapacityUnavailableException"))
    }

    @Test
    fun `credential and infrastructure text is absent from the study reader`() {
        val message = ExportService.withFailureCause(
            "Export generation failed; retry scheduled",
            java.sql.SQLException(
                "connection to 10.42.0.8:5432 refused password=hunter2; SELECT * FROM participant_private",
            ),
        )

        assertFalse(message.contains("hunter2"))
        assertFalse(message.contains("10.42.0.8"))
        assertFalse(message.contains("5432"))
        assertFalse(message.contains("participant_private"))
        assertTrue(message.contains("category=database"))
    }

    @Test
    fun `a runaway cause cannot fill the error_message column`() {
        val message = ExportService.withFailureCause(
            "Export generation failed; retry scheduled",
            IllegalStateException("x".repeat(5000)),
        )

        assertTrue(message.length < 160)
        assertFalse(message.contains("x".repeat(20)))
    }
}
