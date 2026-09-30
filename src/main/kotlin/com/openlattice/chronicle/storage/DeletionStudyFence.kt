package com.openlattice.chronicle.storage

import java.sql.Connection
import java.util.UUID

/** Writers take study fences before row locks; erasure takes the same fences exclusively. */
internal object DeletionStudyFence {
    fun shared(connection: Connection, studyId: UUID) = shared(connection, listOf(studyId))

    fun shared(connection: Connection, studyIds: Collection<UUID>) {
        check(!connection.autoCommit) { "Deletion fencing requires an active transaction" }
        connection.prepareStatement(
            "SELECT pg_advisory_xact_lock_shared(hashtextextended('chronicle-deletion:' || ?::text, 0))",
        ).use { statement ->
            studyIds.distinct().sortedBy(UUID::toString).forEach { studyId ->
                statement.setObject(1, studyId)
                statement.execute()
            }
        }
    }
}
