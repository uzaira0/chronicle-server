package com.openlattice.chronicle.services.delete

import com.openlattice.chronicle.storage.StreamingReadTransaction
import com.openlattice.chronicle.services.upload.UploadType
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.UPLOAD_BUFFER
import com.openlattice.chronicle.storage.ParticipantPurgeCutoff
import com.openlattice.chronicle.storage.PostgresColumns
import java.sql.Connection
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/** Retains captures newer than the purge watermark on the deletion transaction. */
internal class BufferedUploadPurgeRepository(private val connection: Connection) {
    fun retain(studyId: UUID, participantId: String, cutoff: OffsetDateTime) {
        val dataColumn = PostgresColumns.UPLOAD_DATA.name
        connection.prepareStatement(
            "SELECT ctid::text, $dataColumn, ${PostgresColumns.UPLOAD_TYPE.name} FROM ${UPLOAD_BUFFER.name} " +
                "WHERE $STUDY_ERASURE_PREDICATE AND participant_id = ? FOR UPDATE",
        ).use { statement ->
            statement.bindErasureStudyId(studyId)
            statement.setString(2, participantId)
            StreamingReadTransaction.configure(statement, fetchSize = 1)
            statement.executeQuery().use { rows ->
                retainRows(rows, cutoff)
            }
        }
    }

    private fun retainRows(rows: ResultSet, cutoff: OffsetDateTime) {
        val dataColumn = PostgresColumns.UPLOAD_DATA.name
        connection.prepareStatement("UPDATE ${UPLOAD_BUFFER.name} SET $dataColumn = ?::jsonb WHERE ctid = ?::tid").use { update ->
            while (rows.next()) {
                val raw = rows.getString(2)
                val uploadType = UploadType.valueOf(rows.getString(3))
                val retained = ParticipantPurgeCutoff.retainBufferedPayload(
                    raw, uploadType, cutoff,
                )
                update.setString(1, retained)
                update.setString(2, rows.getString(1))
                check(update.executeUpdate() == 1) { "Buffered purge row disappeared" }
            }
        }
    }
}
