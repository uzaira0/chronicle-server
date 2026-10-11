package com.openlattice.chronicle.storage

import java.sql.Connection
import java.util.UUID

/** Writers take study fences before row locks; erasure takes the same fences exclusively. */
internal object DeletionStudyFence {
    const val EXPORT_LOCK_KEY_SQL = "hashtextextended('chronicle-export:' || ?::text, 0)"

    fun exclusive(connection: Connection, studyId: UUID) {
        if (!tryExclusive(connection, studyId)) {
            throw org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,
                com.openlattice.chronicle.i18n.Messages.get("error.deletion.studyBusy"),
            )
        }
    }

    fun tryExclusive(connection: Connection, studyId: UUID): Boolean {
        // Short writes and competing deletions finish before quarantine. Long exports use a
        // separate fence so they cannot queue erasure ahead of every subsequent upload.
        connection.prepareStatement(
            "SELECT pg_advisory_xact_lock(hashtextextended('chronicle-deletion:' || ?::text, 0))",
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.execute()
        }
        return connection.prepareStatement(
            "SELECT pg_try_advisory_xact_lock($EXPORT_LOCK_KEY_SQL)",
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Deletion study lock was not acquired" }
                resultSet.getBoolean(1)
            }
        }
    }

    fun exportShared(connection: Connection, studyId: UUID) {
        check(!connection.autoCommit) { "Export fencing requires an active transaction" }
        connection.prepareStatement("SELECT pg_advisory_xact_lock_shared($EXPORT_LOCK_KEY_SQL)").use { statement ->
            statement.setObject(1, studyId)
            statement.execute()
        }
    }

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
