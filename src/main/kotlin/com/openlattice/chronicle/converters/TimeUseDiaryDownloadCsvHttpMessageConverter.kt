package com.openlattice.chronicle.converters

import com.fasterxml.jackson.dataformat.csv.CsvSchema
import com.openlattice.chronicle.constants.CustomMediaType
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpOutputMessage
import org.springframework.http.converter.AbstractGenericHttpMessageConverter
import java.lang.reflect.Type

/**
 * @author alfoncenzioka &lt;alfonce@openlattice.com&gt;
 */
public open class TimeUseDiaryDownloadCsvHttpMessageConverter : AbstractGenericHttpMessageConverter<TimeUseDiaryPostgresDownloadWrapper>(
    CustomMediaType.TEXT_CSV
) {
    private val csvMapper = downloadCsvMapper()

    override fun read(type: Type, contextClass: Class<*>?, inputMessage: HttpInputMessage): TimeUseDiaryPostgresDownloadWrapper {
        throw UnsupportedOperationException("CSV is not a a supported input format")
    }

    override fun readInternal(
        clazz: Class<out TimeUseDiaryPostgresDownloadWrapper>,
        inputMessage: HttpInputMessage
    ): TimeUseDiaryPostgresDownloadWrapper {
        throw UnsupportedOperationException("CSV is not a a supported input format")
    }

    override fun supports(clazz: Class<*>): Boolean {
        return TimeUseDiaryPostgresDownloadWrapper::class.java.isAssignableFrom(clazz)
    }

    override fun writeInternal(t: TimeUseDiaryPostgresDownloadWrapper, type: Type?, outputMessage: HttpOutputMessage) {
        writeCsvRows(csvMapper.writer(getSchema(t)), outputMessage.body, t)
    }

    private fun getSchema(iterable: TimeUseDiaryPostgresDownloadWrapper): CsvSchema {
        val schemaBuilder = CsvSchema.builder()
        val columns: List<String> = iterable.columnAdvice
        columns.forEach {
            schemaBuilder.addColumn(it)
        }

        return schemaBuilder.setUseHeader(true).build()
    }
}
