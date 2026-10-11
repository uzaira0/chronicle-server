package com.openlattice.chronicle.converters

import com.fasterxml.jackson.dataformat.csv.CsvMapper
import com.fasterxml.jackson.dataformat.csv.CsvSchema
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class CsvFormulaProtectionTest {
    @Test
    fun synchronousCellsEscapeTextAndPreserveNumbers() {
        val mapper = CsvMapper()
        val schema = CsvSchema.builder().addColumn("text").addNumberColumn("number").setUseHeader(true).build()
        for (value in listOf("=2+2", " +cmd", "-formula", "@remote", "\ttext", "\rtext", "\ntext")) {
            val output = ByteArrayOutputStream()
            writeCsvRows(mapper.writer(schema), output, listOf(mapOf("text" to value, "number" to -12)))
            val row = mapper.readerFor(Map::class.java).with(schema).readValues<Map<String, Any>>(output.toByteArray()).next()
            assertEquals("'$value", row["text"])
            assertEquals("-12", row["number"].toString())
        }
    }
}
