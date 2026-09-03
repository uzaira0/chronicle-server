package com.openlattice.chronicle.converters

import com.geekbeast.postgres.streams.BasePostgresIterable

/**
 * Decorates a postgres iterable with a list of columns that can be used to generate a CSV download of data
 * directly from a postgres query. It is expected
 * @param iterable The base postgres iterable to wrap.
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public open class PostgresDownloadWrapper(
    iterable: BasePostgresIterable<Map<String, Any>>
) :
    Iterable<Map<String, Any>> by iterable {
    internal companion object {
        private val DEFAULT_COLUMNS = listOf<String>()
    }

    /**
     * List of columns that are expected in the base postgres iterable. Should be the keys to each element returned
     * by the iterator
     */
    public var columnAdvice: List<String> = DEFAULT_COLUMNS

    public fun withColumnAdvice(columnAdvice: List<String>): PostgresDownloadWrapper {
        this.columnAdvice = columnAdvice
        return this
    }
}
