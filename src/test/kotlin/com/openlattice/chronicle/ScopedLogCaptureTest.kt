package com.openlattice.chronicle

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class ScopedLogCaptureTest {
    private fun configuredLoggerNames(): Set<String> =
        (LogManager.getContext(false) as LoggerContext).configuration.loggers.keys.toSet()

    @Test
    fun closingACaptureLeavesTheLoggerConfigurationAsItFoundIt() {
        // A namespace with no LoggerConfig of its own, like com.openlattice.chronicle in the test config.
        val namespace = "com.openlattice.chronicle.capturefixture${UUID.randomUUID().toString().replace("-", "")}"
        val before = configuredLoggerNames()

        ScopedLogCapture(namespace).use { capture ->
            LogManager.getLogger("$namespace.Inner").info("inside the capture")
            assertEquals(listOf("inside the capture"), capture.messages)
        }

        assertEquals(before, configuredLoggerNames())
        // Events under that namespace reach the root appenders again, as they did before the capture.
        ScopedLogCapture(LogManager.ROOT_LOGGER_NAME).use { root ->
            LogManager.getLogger("$namespace.Later").info("after the capture")
            assertTrue("root lost events after a closed capture: ${root.messages}", "after the capture" in root.messages)
        }
    }

    @Test
    fun aCaptureOnAConfiguredLoggerKeepsThatLoggerConfig() {
        val namespace = "com.openlattice.chronicle.configuredfixture${UUID.randomUUID().toString().replace("-", "")}"
        ScopedLogCapture(namespace).use { outer ->
            val configured = configuredLoggerNames()
            ScopedLogCapture(namespace).use { inner ->
                LogManager.getLogger(namespace).info("nested")
                assertEquals(listOf("nested"), inner.messages)
            }
            // The inner capture found the outer capture's LoggerConfig and must not remove it.
            assertEquals(configured, configuredLoggerNames())
            LogManager.getLogger(namespace).info("outer still attached")
            assertEquals(listOf("nested", "outer still attached"), outer.messages)
        }
    }

    @Test
    fun testsCaptureLogsOnlyThroughScopedLogCapture() {
        // Logger.addAppender creates a LoggerConfig that outlives the appender; only ScopedLogCapture removes it.
        val offenders = File("src/test/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "ScopedLogCapture.kt" && it.name != "ScopedLogCaptureTest.kt" }
            .filter { file -> file.readText().contains(".addAppender(") }
            .map { it.path }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
