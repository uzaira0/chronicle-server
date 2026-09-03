package com.openlattice.chronicle.pods.tables

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
public open class PostgresDataTablesPod {
    // reason: reflective field enumeration over PostgresDataTables requires spreading the Field[]
    // into Stream.of; the arrays are small and fixed at class-load time
    @Suppress("SpreadOperator")
    @Bean
    public fun postgresDataTables(): PostgresTables {
        return PostgresTables {
            Stream.concat(
                    Stream.of(*PostgresDataTables::class.java.fields),
                    Stream.of(*PostgresDataTables::class.java.declaredFields)
            ).filter { field: Field ->
                (Modifier.isStatic(field.modifiers)
                        && Modifier.isFinal(field.modifiers))
            }.filter { field: Field ->
                field.type == PostgresTableDefinition::class.java
            }.map { field: Field ->
                try {
                    return@map field[null] as PostgresTableDefinition
                } catch (e: IllegalAccessException) {
                    logger.warn("Unable to access table definition field {}", field.name, e)
                    return@map null
                }
            }.filter { obj: PostgresTableDefinition? ->
                Objects.nonNull(
                        obj
                )
            }
        }
    }

    internal companion object {
        private val logger = LoggerFactory.getLogger(PostgresDataTablesPod::class.java)
    }
}
