/*
 * Copyright (C) 2018. OpenLattice, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * You can contact the owner of the copyright at support@openlattice.com
 */
package com.openlattice.chronicle.converters

import com.fasterxml.jackson.databind.ObjectWriter
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.csv.CsvGenerator
import com.fasterxml.jackson.dataformat.csv.CsvMapper
import com.fasterxml.jackson.dataformat.csv.CsvSchema
import com.fasterxml.jackson.datatype.guava.GuavaModule
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.blackbird.BlackbirdModule
import com.geekbeast.postgres.PostgresDatatype
import com.openlattice.chronicle.services.export.ExportFileWriter
import com.openlattice.chronicle.constants.CustomMediaType
import com.openlattice.chronicle.storage.PostgresColumns
import com.openlattice.chronicle.storage.PostgresEventColumns
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpOutputMessage
import org.springframework.http.converter.AbstractGenericHttpMessageConverter
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.http.converter.HttpMessageNotWritableException
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.Type
import java.util.*
import java.util.function.Consumer

public open class PostgresDownloadCsvHttpMessageConverter : AbstractGenericHttpMessageConverter<PostgresDownloadWrapper>(
    CustomMediaType.TEXT_CSV
) {
    internal companion object {
        private val resolutionMap = PostgresColumns.columnTypes + PostgresEventColumns.columnTypes
        private val csvTypes = EnumMap<PostgresDatatype, CsvSchema.ColumnType>(PostgresDatatype::class.java)

        init {
            csvTypes[PostgresDatatype.TEXT] = CsvSchema.ColumnType.STRING
            csvTypes[PostgresDatatype.TEXT_128] = CsvSchema.ColumnType.STRING
            csvTypes[PostgresDatatype.TEXT_256] = CsvSchema.ColumnType.STRING
            csvTypes[PostgresDatatype.TEXT_UUID] = CsvSchema.ColumnType.STRING
            csvTypes[PostgresDatatype.TEXT_ARRAY] = CsvSchema.ColumnType.ARRAY
            csvTypes[PostgresDatatype.JSONB] = CsvSchema.ColumnType.STRING
            csvTypes[PostgresDatatype.UUID] = CsvSchema.ColumnType.STRING
            csvTypes[PostgresDatatype.UUID_ARRAY] = CsvSchema.ColumnType.ARRAY
            csvTypes[PostgresDatatype.TIMESTAMPTZ] = CsvSchema.ColumnType.STRING
            csvTypes[PostgresDatatype.BIGINT] = CsvSchema.ColumnType.NUMBER
            csvTypes[PostgresDatatype.DOUBLE] = CsvSchema.ColumnType.NUMBER
            csvTypes[PostgresDatatype.REAL] = CsvSchema.ColumnType.NUMBER
            csvTypes[PostgresDatatype.BOOLEAN] = CsvSchema.ColumnType.BOOLEAN
            csvTypes[PostgresDatatype.DATE] = CsvSchema.ColumnType.STRING
            csvTypes[PostgresDatatype.INTEGER] = CsvSchema.ColumnType.NUMBER
        }
    }

    private val csvMapper = downloadCsvMapper()

    @Throws(HttpMessageNotReadableException::class)
    override fun read(type: Type, contextClass: Class<*>?, inputMessage: HttpInputMessage): PostgresDownloadWrapper {
        throw UnsupportedOperationException("CSV is not a supported input format")
    }

    @Throws(IOException::class, HttpMessageNotWritableException::class)
    override fun writeInternal(t: PostgresDownloadWrapper, type: Type?, outputMessage: HttpOutputMessage) {
        writeCsvRows(csvMapper.writer(getSchema(t)), outputMessage.body, t)
    }

    override fun supports(clazz: Class<*>): Boolean {
        return PostgresDownloadWrapper::class.java.isAssignableFrom(clazz)
    }

    @Throws(HttpMessageNotReadableException::class)
    override fun readInternal(
        clazz: Class<out PostgresDownloadWrapper>,
        inputMessage: HttpInputMessage
    ): PostgresDownloadWrapper {
        throw UnsupportedOperationException("CSV is not a supported input format")
    }

    private fun getSchema(iterable: PostgresDownloadWrapper): CsvSchema {

        val schemaBuilder = CsvSchema.builder()
        val columns: List<String> = iterable.columnAdvice
        columns.forEach(Consumer { col: String ->
            schemaBuilder.addColumn(col, csvTypes.getValue(resolutionMap.getOrDefault(col, PostgresDatatype.TEXT)))
        })

        return schemaBuilder.setUseHeader(true).build()
    }
}

internal fun downloadCsvMapper(): CsvMapper = CsvMapper().apply {
    registerModule(BlackbirdModule())
    registerModule(GuavaModule())
    registerModule(JavaTimeModule())
    registerModule(Jdk8Module())
    configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
    configure(CsvGenerator.Feature.STRICT_CHECK_FOR_QUOTING, true)
}

/**
 * Streams [rows] as CSV and finalizes the writer (header for an empty result, flush, end of stream)
 * only on success. A query, mapping or client-write failure must not be closed into what looks like a
 * complete 200 download, so it propagates with the writer unclosed. The row iterator is closed on every
 * path so its pooled connection is returned at once instead of at the iterator watchdog's expiry.
 */
internal fun spreadsheetSafeCsvValue(value: Any?): Any? = when (value) {
    is String -> ExportFileWriter.spreadsheetSafeString(value)
    is Map<*, *> -> value.mapValues { spreadsheetSafeCsvValue(it.value) }
    is Iterable<*> -> value.map(::spreadsheetSafeCsvValue)
    is Array<*> -> value.map(::spreadsheetSafeCsvValue)
    else -> value
}

internal fun writeCsvRows(writer: ObjectWriter, out: OutputStream, rows: Iterable<*>) {
    val iterator = rows.iterator()
    try {
        val sequence = writer.writeValues(out)
        iterator.forEach { sequence.write(spreadsheetSafeCsvValue(it)) }
        sequence.close()
    } finally {
        (iterator as? AutoCloseable)?.close()
    }
}
