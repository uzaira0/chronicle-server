package com.openlattice.chronicle.services.studies

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import java.sql.Connection
import java.util.UUID

/** Every deletion status the schema allows has a decided study visibility. */
class StudyDeletionGuardStatusTest {
    companion object {
        private val BLOCKING = setOf("QUARANTINED", "ERASING", "VERIFYING", "FAILED", "COMPLETED")
        private val VISIBLE = setOf("PREVIEW", "CANCELLED")

        @ClassRule
        @JvmField
        val postgres = ChronicleContractTestSchema.prodPostgresContainer("study_deletion_guard_status")

        @BeforeClass
        @JvmStatic
        fun migrate() = ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)

        @AfterClass
        @JvmStatic
        fun clear() = ChronicleContractTestSchema.clearSchema(postgres)
    }

    @Test
    fun aStudyErasureBlocksTheStudyExactlyInItsBlockingStatuses() {
        postgres.createConnection("").use { connection ->
            val statuses = schemaStatuses(connection)
            assertEquals(statuses, BLOCKING + VISIBLE)
            val studyByStatus = statuses.associateWith { status -> UUID.randomUUID().also { insertErasure(connection, it, status) } }

            val blocked = connection.prepareStatement(StudyDeletionGuard.FIND_BLOCKED_STUDIES_SQL).use { statement ->
                statement.setArray(1, connection.createArrayOf("uuid", studyByStatus.values.toTypedArray()))
                statement.executeQuery().use { rows -> buildSet { while (rows.next()) add(rows.getObject(1, UUID::class.java)) } }
            }

            assertEquals(BLOCKING, studyByStatus.filterValues(blocked::contains).keys)
        }
    }

    private fun schemaStatuses(connection: Connection): Set<String> = connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'data_deletion_operations_status_check'",
        ).use { rows ->
            check(rows.next()) { "data_deletion_operations has no status check" }
            Regex("'([A-Z_]+)'").findAll(rows.getString(1)).map { it.groupValues[1] }.toSet()
        }
    }

    private fun insertErasure(connection: Connection, studyId: UUID, status: String) {
        val leased = status == "ERASING" || status == "VERIFYING"
        val cancelled = status == "CANCELLED"
        connection.prepareStatement(
            """
            INSERT INTO data_deletion_operations (operation_id, study_id, mode, status, requested_by, idempotency_key,
                registry_version, worker_lease_token, worker_lease_expires_at, cancelled_by, cancelled_at)
            VALUES (?, ?, 'STUDY_ERASURE', ?, 'owner', ?, 1, ?, CASE WHEN ? THEN now() + interval '1 hour' END,
                CASE WHEN ? THEN 'owner' END, CASE WHEN ? THEN now() END)
            """.trimIndent(),
        ).use {
            it.setObject(1, UUID.randomUUID())
            it.setObject(2, studyId)
            it.setString(3, status)
            it.setObject(4, UUID.randomUUID())
            it.setObject(5, if (leased) UUID.randomUUID() else null)
            it.setBoolean(6, leased)
            it.setBoolean(7, cancelled)
            it.setBoolean(8, cancelled)
            it.executeUpdate()
        }
    }
}
