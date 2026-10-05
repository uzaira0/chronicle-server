package com.openlattice.chronicle.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Logs name participants only by keyed reference ([LogSanitizer.stableFingerprints]), never by raw ID. */
class ParticipantLogGuardTest {
    @Test
    fun `no log template prints raw participant id sets`() {
        val offenders = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> !line.trimStart().startsWith("//") && "participants = {}" in line }
                .map { (index, _) -> "${file.path}:${index + 1}" }
        }.toList()
        assertEquals("use participantRefs = {} with LogSanitizer.stableFingerprints", emptyList<String>(), offenders)
    }
}
