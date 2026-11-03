package com.openlattice.chronicle.upgrades

import java.io.File

/** Versioned SQL migrations the server ships; tests count them instead of assuming V1 alone. */
internal val shippedMigrationCount: Int =
    checkNotNull(File("src/main/resources/db/migration").listFiles()) { "migration directory missing" }
        .count { Regex("""V\d+__.+\.sql""").matches(it.name) }
