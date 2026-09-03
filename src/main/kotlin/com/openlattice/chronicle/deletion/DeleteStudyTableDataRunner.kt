package com.openlattice.chronicle.deletion

import com.geekbeast.rhizome.jobs.JobStatus
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.services.jobs.AbstractChronicleDeleteJobRunner
import com.openlattice.chronicle.services.jobs.ChronicleJob
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.util.SqlIdentifierValidator
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.OffsetDateTime
import java.util.UUID

public open class DeleteStudyTableDataRunner(
    private val storageResolver: StorageResolver,
) : AbstractChronicleDeleteJobRunner<DeleteStudyTableData>() {

    internal companion object {
        private val logger = LoggerFactory.getLogger(DeleteStudyTableDataRunner::class.java)
    }

    override fun runJob(connection: Connection, job: ChronicleJob): List<AuditableEvent> {
        job.definition as DeleteStudyTableData

        val tables = job.definition.tables
        require(tables.isNotEmpty()) { "DeleteStudyTableData requires at least one table" }
        val storage = tables.first().storage
        require(tables.all { it.storage == storage }) { "DeleteStudyTableData cannot mix storage backends" }

        val deletedRows = when (storage) {
            StudyDeletionStorage.EVENT -> {
                val eventDataSourceName = requireNotNull(job.definition.eventDataSourceName) {
                    "Event-storage study deletion requires eventDataSourceName captured before study deletion"
                }
                val (_, eventHds) = storageResolver.getDataSource(eventDataSourceName)
                eventHds.connection.use { eventConnection ->
                    tables.sumOf { table -> deleteStudyRows(eventConnection, job.definition.studyId, table) }
                }
            }
            StudyDeletionStorage.PLATFORM -> {
                tables.sumOf { table -> deleteStudyRows(connection, job.definition.studyId, table) }
            }
        }

        job.deletedRows = deletedRows
        job.updatedAt = OffsetDateTime.now()
        job.completedAt = job.updatedAt
        job.status = JobStatus.FINISHED
        updateFinishedDeleteJob(connection, job)

        return tables.map { table ->
            AuditableEvent(
                AclKey(job.definition.studyId),
                job.securablePrincipalId,
                job.principal,
                eventType = table.auditEventType,
                data = mapOf("definition" to job.definition, "table" to table.tableName),
                study = job.definition.studyId,
            )
        }
    }

    private fun deleteStudyRows(
        connection: Connection,
        studyId: UUID,
        table: StudyDeletionTable,
    ): Long {
        val tableName = SqlIdentifierValidator.validateTableName(table.tableName)
        val studyIdColumnName = SqlIdentifierValidator.validateIdentifier(table.studyIdColumnName)
        logger.info(
            "Deleting study data from {} with studyId = {}",
            tableName,
            studyId,
        )
        val sql = "DELETE FROM $tableName WHERE $studyIdColumnName = ?"
        return connection.prepareStatement(sql).use { ps ->
            bindStudyId(ps, 1, studyId, table.storage)
            ps.executeUpdate().toLong()
        }
    }

    private fun bindStudyId(
        ps: PreparedStatement,
        parameterIndex: Int,
        studyId: UUID,
        storage: StudyDeletionStorage,
    ) {
        when (storage) {
            StudyDeletionStorage.EVENT -> ps.setString(parameterIndex, studyId.toString())
            StudyDeletionStorage.PLATFORM -> ps.setObject(parameterIndex, studyId)
        }
    }

    override fun accepts(): Class<DeleteStudyTableData> = DeleteStudyTableData::class.java
}
