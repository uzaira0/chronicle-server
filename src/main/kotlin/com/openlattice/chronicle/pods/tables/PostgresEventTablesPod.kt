package com.openlattice.chronicle.pods.tables

import com.openlattice.chronicle.storage.PostgresEventTables
import com.openlattice.chronicle.storage.PostgresDataTables
import com.geekbeast.postgres.PostgresTableDefinition
import com.geekbeast.postgres.PostgresTables
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.*
import java.util.stream.Stream

/**
 * When included as a pod this class automatically registers core openlattice tables for running the system.
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
@Configuration
@Profile(PostgresDataTables.POSTGRES_DATA_ENVIRONMENT)
public open class PostgresEventTablesPod {
    private companion object {
        private val logger = LoggerFactory.getLogger(PostgresEventTablesPod::class.java)
    }

    // reason: vararg API — Stream.of requires spreading the reflected field array; the array is the
    // small, fixed set of PostgresEventTables fields
    @Suppress("SpreadOperator")
    @Bean
    public fun postgresEventTables(): PostgresTables {
        return PostgresTables {
            Stream.of(*PostgresEventTables::class.java.fields)
                    .filter { field: Field ->
                        (Modifier.isStatic(field.modifiers) && Modifier.isFinal(field.modifiers))
                    }.filter { field: Field ->
                        PostgresTableDefinition::class.java.isAssignableFrom(field.type)
                    }
                    .map { field: Field ->
                        try {
                            return@map field[null] as PostgresTableDefinition
                        } catch (e: IllegalAccessException) {
                            logger.warn("Skipping inaccessible table field {}: {}", field.name, e.message)
                            return@map null
                        }
                    }.filter { obj: PostgresTableDefinition? ->
                        Objects.nonNull(
                                obj
                        )
                    }
        }
    }
}
