package com.openlattice.chronicle.storage

import com.geekbeast.postgres.PostgresColumnDefinition
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public fun odtFromUsageEventColumn(value: Any?): OffsetDateTime? {
    if (value == null) return null
    return when (value) {
        is String -> OffsetDateTime.parse(value)
        is OffsetDateTime -> value
        else -> throw UnsupportedOperationException("${value.javaClass.canonicalName} is not a supported date time class.")
    }
}

public fun zdtFromAndroidColumns(value: Any?, timezone: String): ZonedDateTime? {
    if (value == null) return null
    return when (value) {
        is String -> OffsetDateTime.parse(value)
        is OffsetDateTime -> value
        else -> throw UnsupportedOperationException("${value.javaClass.canonicalName} is not a supported date time class.")
    }.atZoneSameInstant(ZoneId.of(timezone))
}

internal fun reflectedPostgresColumns(
    owner: Class<*>,
    inaccessible: (Field, IllegalAccessException) -> Unit,
): List<PostgresColumnDefinition> = (owner.fields.asList() + owner.declaredFields)
    .filter { Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) }
    .filter { PostgresColumnDefinition::class.java.isAssignableFrom(it.type) }
    .mapNotNull { field ->
        try {
            field[null] as PostgresColumnDefinition
        } catch (error: IllegalAccessException) {
            inaccessible(field, error)
            null
        }
    }
