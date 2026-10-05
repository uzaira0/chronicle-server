package com.openlattice.chronicle.converters

import com.fasterxml.jackson.dataformat.csv.CsvMapper
import com.fasterxml.jackson.dataformat.csv.CsvSchema
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class CsvFormulaProtectionTest {
    @Test
    fun synchronousAndLegacyCellsEscapeTextAndPreserveNumbers() {
        val mapper = CsvMapper()
        val schema = CsvSchema.builder().addColumn("text").addNumberColumn("number").setUseHeader(true).build()
        for (value in listOf("=2+2", " +cmd", "-formula", "@remote", "\ttext", "\rtext", "\ntext")) {
            val output = ByteArrayOutputStream()
            writeCsvRows(mapper.writer(schema), output, listOf(mapOf("text" to value, "number" to -12)))
            val row = mapper.readerFor(Map::class.java).with(schema).readValues<Map<String, Any>>(output.toByteArray()).next()
            assertEquals("'$value", row["text"])
            assertEquals("-12", row["number"].toString())
            val legacy = LegacyPostgresDownloadWrapper(listOf(mapOf("text" to setOf<Any>(value), "number" to setOf<Any>(-12))))
                .withColumnAdvice(listOf("text", "number"))
            val message = org.springframework.mock.http.MockHttpOutputMessage()
            LegacyPostgresDownloadCsvHttpMessageConverter().write(legacy, com.openlattice.chronicle.constants.CustomMediaType.TEXT_CSV, message)
            val legacyRow = mapper.readerFor(Map::class.java).with(schema).readValues<Map<String, Any>>(message.bodyAsBytes).next()
            assertEquals("'$value", legacyRow["text"])
            assertEquals("-12", legacyRow["number"].toString())
        }
    }
}
