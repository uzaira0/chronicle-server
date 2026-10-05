package com.openlattice.chronicle.observability

import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.RollingFileAppender
import org.apache.logging.log4j.core.appender.rolling.DefaultRolloverStrategy
import org.apache.logging.log4j.core.config.ConfigurationSource
import org.apache.logging.log4j.core.config.xml.XmlConfiguration
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class LogRetentionConfigurationTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `file log retention removes raw rollover leftovers and caps application logs but never audit`() {
        val dir = temporary.root.toPath()
        val xml = requireNotNull(javaClass.classLoader.getResource("log4j2.xml")).readText()
            .replace("\${env:LOG_DIR:-/var/log/chronicle}", dir.toString())
            .replace("\${env:AUDIT_LOG_DIR:-/var/log/chronicle}", dir.toString())
        val config = XmlConfiguration(LoggerContext("log-retention-test"), ConfigurationSource(xml.byteInputStream()))
        config.initialize()
        try {
            val now = System.currentTimeMillis()
            // Sparse files: logical sizes count toward IfAccumulatedFileSize without using disk.
            fun file(name: String, ageDays: Long, bytes: Long = 1): String {
                val path = dir.resolve(name)
                RandomAccessFile(path.toFile(), "rw").use { it.setLength(bytes) }
                Files.setLastModifiedTime(path, FileTime.fromMillis(now - ageDays * 86_400_000L))
                return name
            }
            val gb = 1L shl 30
            val kept = listOf(
                file("chronicle-json.log", 40), // active file, never matched
                file("chronicle-09-30-26-00-00-00-2.log", 0), // recent raw leftover
                file("chronicle-09-29-26-00-00-00-1.log.gz", 1, 6 * gb),
                file("audit.2025-01-01.log.gz", 400, 20 * gb), // no size cap on audit
            )
            val deleted = listOf(
                file("chronicle-json-09-28-26-00-00-00-1.log.gz", 2, 6 * gb), // over the 10GB total
                file("chronicle-01-01-26-00-00-00-1.log", 40), // raw leftover past 30 days
                file("audit.2019-01-01.log.gz", 2200),
                file("audit.2019-01-02.log", 2200), // raw audit leftover past 6 years
            )
            for (name in listOf("RollingFile", "AuditFile")) {
                val appender = config.getAppender<RollingFileAppender>(name)
                (appender.manager.rolloverStrategy as DefaultRolloverStrategy).customActions.forEach { it.execute() }
            }
            val present = (kept + deleted).filter { Files.exists(dir.resolve(it)) }
            assertEquals(kept, present)
        } finally {
            config.stop()
        }
    }
}
