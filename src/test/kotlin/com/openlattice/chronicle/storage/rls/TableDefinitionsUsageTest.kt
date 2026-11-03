package com.openlattice.chronicle.storage.rls

import com.geekbeast.postgres.PostgresTableDefinition
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class TableDefinitionsUsageTest {
    @Test
    fun everyBootstrappedTableHasAProductionUser() {
        val production = Files.walk(Path.of("src/main/kotlin")).use { files ->
            files.filter { it.toString().endsWith(".kt") }
                .filter { it.fileName.toString() !in setOf(
                    "ChroniclePostgresTables.kt", "ChronicleDataAssetRegistry.kt", "DeletionTableLockOrder.kt",
                ) }
                .map { Files.readString(it) }.toList().joinToString("\n")
        }
        val unused = ChroniclePostgresTables::class.java.fields
            .filter {
                Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) &&
                    PostgresTableDefinition::class.java.isAssignableFrom(it.type)
            }
            .filter { !Regex("\\b${it.name}\\b").containsMatchIn(production) }.map { it.name }
        assertTrue("Bootstrapped tables without production users: $unused", unused.isEmpty())
    }
}
