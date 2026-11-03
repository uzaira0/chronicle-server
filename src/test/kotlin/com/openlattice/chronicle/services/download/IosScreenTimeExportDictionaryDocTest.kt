package com.openlattice.chronicle.services.download

import com.openlattice.chronicle.study.ParticipantDataType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The researcher column dictionary of the iOS Screen Time export names exactly the exported columns. */
class IosScreenTimeExportDictionaryDocTest {
    @Test fun contractDocsDoNotAdvertiseRemovedCollectors() {
        val historical = setOf("01", "02", "04", "08", "09")
        val unsupported = Regex("SensorKit|SRSensor|IOSSensor|ambient[_ -]?audio|decode-only|retired", RegexOption.IGNORE_CASE)
        File("../chronicle-models/docs/shared-contracts").listFiles()!!
            .filter { it.extension == "md" }.forEach { doc ->
                val text = doc.readText()
                if (doc.name.take(2) in historical) {
                    assertTrue("${doc.name} must identify its pre-reset scope", text.lineSequence().take(5)
                        .any { it.startsWith("> Superseded design record") })
                } else {
                    assertEquals("${doc.name} describes unsupported collection contracts", emptyList<String>(),
                        unsupported.findAll(text).map { it.value }.toList())
                }
            }
    }

    @Test fun dictionaryListsEveryExportedColumn() {
        val lines = File("../docs/PILOT-DATA.md").readLines()
        val section = lines.indexOfFirst { it == "## iOS Screen Time" }
        val header = lines.drop(section).indexOfFirst { it.startsWith("| Columns |") } + section
        val documented = lines.drop(header + 2).takeWhile { it.startsWith("|") }
            .flatMap { row -> Regex("`([a-z_]+)`").findAll(row.split("|")[1]).map { it.groupValues[1] } }
            .toSet()
        val exported = DataDownloadService.COLLECTION_EXPORTS.getValue(ParticipantDataType.IOSScreenTime)
            .table.columns.map { it.name }.toSet()
        assertEquals(exported, documented)
    }
}
