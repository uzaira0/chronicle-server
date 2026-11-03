package com.openlattice.chronicle

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.apache.logging.log4j.core.layout.PatternLayout
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Scoped capture with exception text and guaranteed cleanup through use. The only place tests
 * attach appenders (ScopedLogCaptureTest enforces it): for a name without a LoggerConfig,
 * Logger.addAppender creates one that copies the parent's additivity (false for root in
 * src/test/resources/log4j2.properties), so leaving it behind cuts the whole namespace off the
 * root appenders for every later test in the JVM. close() removes what init created.
 */
class ScopedLogCapture(
    name: String,
    minimumLevel: Level = Level.ALL,
    pattern: String = "%m%n%ex",
) : AutoCloseable {
    constructor(type: Class<*>, minimumLevel: Level = Level.ALL, pattern: String = "%m%n%ex") :
        this(type.name, minimumLevel, pattern)

    val messages = CopyOnWriteArrayList<String>()
    private val logger = LogManager.getLogger(name) as Logger
    private val captureLayout = PatternLayout.newBuilder().withPattern(pattern).build()
    private val appender = object : AbstractAppender("capture-${UUID.randomUUID()}", null, null, true, Property.EMPTY_ARRAY) {
        override fun append(event: LogEvent) {
            if (event.level.isMoreSpecificThan(minimumLevel)) messages.add(captureLayout.toSerializable(event).trimEnd())
        }
    }
    private val createdLoggerConfig = logger.context.configuration.let { configuration ->
        val hadOwnConfig = configuration.getLoggerConfig(logger.name).name == logger.name
        appender.start()
        logger.addAppender(appender)
        if (hadOwnConfig) null else configuration.loggers[logger.name]
    }

    override fun close() {
        logger.removeAppender(appender)
        appender.stop()
        val configuration = logger.context.configuration
        // Only the LoggerConfig this capture created, and only while it is still installed.
        if (createdLoggerConfig != null && configuration.loggers[logger.name] === createdLoggerConfig) {
            configuration.removeLogger(logger.name)
            logger.context.updateLoggers()
        }
    }
}
