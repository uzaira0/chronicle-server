package com.openlattice.chronicle.storage

import com.openlattice.chronicle.storage.rls.RLSDataSources
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** A study read and the primary pool that holds its disclosure generation; a capability, not a value. */
internal class StudyReadScope(val studyId: UUID, val primary: HikariDataSource)

internal fun StorageResolver.studyReadScope(studyId: UUID) = StudyReadScope(studyId, getPlatformStorage())

/** The database advances this generation in the same transaction as withdrawal and quarantine. */
internal class StudyDisclosureGeneration(private val connection: Connection) {
    companion object {
        const val COLUMN = "disclosure_generation"
        private const val CANCELLED_SQL_STATE = "57014"
    }

    fun checkpoint(scope: StudyReadScope, cursorPool: HikariDataSource): Checkpoint {
        check(connection.transactionIsolation == Connection.TRANSACTION_READ_COMMITTED) {
            "Streaming disclosure requires READ COMMITTED generation checks"
        }
        return Checkpoint(
            source = this,
            scope = scope,
            useCursor = RLSDataSources.samePool(cursorPool, scope.primary),
            generation = read(scope.studyId),
        )
    }

    internal fun read(studyId: UUID): UUID = connection.prepareStatement(
        "SELECT $COLUMN FROM studies WHERE study_id = ?",
    ).use {
        it.setObject(1, studyId)
        it.executeQuery().use { rows ->
            if (!rows.next()) throw SQLException("Study disclosure is unavailable", CANCELLED_SQL_STATE)
            rows.getObject(1, UUID::class.java)
        }
    }

    class Checkpoint internal constructor(
        private val source: StudyDisclosureGeneration,
        private val scope: StudyReadScope,
        private val useCursor: Boolean,
        private val generation: UUID,
    ) {
        fun requireCurrent() {
            val current = if (useCursor) source.read(scope.studyId) else scope.primary.connection.use {
                StudyDisclosureGeneration(it).read(scope.studyId)
            }
            if (current != generation) throw SQLException("Study disclosure was cancelled", CANCELLED_SQL_STATE)
        }
    }
}
