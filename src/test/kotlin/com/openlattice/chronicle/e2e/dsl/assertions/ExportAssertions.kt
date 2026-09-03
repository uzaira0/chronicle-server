package com.openlattice.chronicle.e2e.dsl.assertions

import org.junit.Assert.assertTrue

object ExportAssertions {

    fun assertCsvHasRows(bytes: ByteArray, atLeast: Int) {
        val lines = bytes.toString(Charsets.UTF_8)
            .lines()
            .filter { it.isNotBlank() }
        // Subtract 1 for the header row
        val dataRows = (lines.size - 1).coerceAtLeast(0)
        assertTrue(
            "Expected CSV to have at least $atLeast data rows, but got $dataRows",
            dataRows >= atLeast
        )
    }
}
