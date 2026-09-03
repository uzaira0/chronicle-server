package com.openlattice.chronicle.converters

import com.geekbeast.postgres.streams.BasePostgresIterable

/**
 * @author alfoncenzioka &lt;alfonce@openlattice.com&gt;
 */
public open class TimeUseDiaryPostgresDownloadWrapper(
    iterable: BasePostgresIterable<List<Map<String, Any>>>
): Iterable<List<Map<String, Any>>> by iterable {

    internal companion object {
        private val DEFAULT_COLUMNS = listOf<String>()
    }

    public var columnAdvice: List<String> = DEFAULT_COLUMNS

    public fun withColumnAdvice(columnAdvice: List<String>): TimeUseDiaryPostgresDownloadWrapper {
        this.columnAdvice = columnAdvice
        return this
    }
}
