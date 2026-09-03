package com.openlattice.chronicle.services.jobs

import com.openlattice.chronicle.auditing.AuditableEvent
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public open class DefaultJobRunner<T : ChronicleJobDefinition>(private val clazz: Class<T>) : AbstractChronicleJobRunner<T>() {

    internal companion object {
        private val logger = LoggerFactory.getLogger(DefaultJobRunner::class.java)
        public fun <T : ChronicleJobDefinition> getDefaultJobRunner(jobDefinition: T): ChronicleJobRunner<T> {
            return DefaultJobRunner(jobDefinition.javaClass)
        }
    }

    override fun runJob(connection: Connection, job: ChronicleJob): List<AuditableEvent> {
        logger.warn("No job handler was found. Using default ")
        return listOf()
    }

    override fun accepts(): Class<T> {
        return clazz
    }
}
