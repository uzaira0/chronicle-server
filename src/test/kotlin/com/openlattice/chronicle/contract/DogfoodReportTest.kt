package com.openlattice.chronicle.contract

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DogfoodReportTest {
    @Test fun reportRunsAgainstTheCurrentDeviceSchema() {
        val postgres = ChronicleContractTestSchema.sharedPostgres
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("scripts/chronicle-dogfood-report.sh")) }
        val builder = ProcessBuilder("bash", root.resolve("scripts/chronicle-dogfood-report.sh").toString())
            .redirectErrorStream(true)
        builder.environment().putAll(mapOf("CHRONICLE_STUDY_ID" to UUID.randomUUID().toString(),
            "CHRONICLE_PARTICIPANT_ID" to "synthetic-report", "CHRONICLE_POSTGRES_CONTAINER" to postgres.containerId,
            "POSTGRES_USER" to postgres.username, "POSTGRES_DB" to postgres.databaseName, "POSTGRES_PASSWORD" to postgres.password))
        val process = builder.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
        assertTrue(output.contains("App Version / Device Inventory"))
    }
}
