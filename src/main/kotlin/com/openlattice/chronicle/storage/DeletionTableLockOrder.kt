package com.openlattice.chronicle.storage

import java.sql.Connection
import java.util.UUID

/** Global verification locks and drains acquire every writable table in this order. */
internal object DeletionTableLockOrder {
    val comparator: Comparator<String> = compareBy<String> {
        when (it) {
            "studies" -> 0
            "upload_buffer" -> 1
            "upload_diagnostic_erasures" -> 3
            else -> 2
        }
    }.thenBy { it }

    fun lockDrain(
        connection: Connection,
        destination: String,
        batchSize: Int = 128,
        studyId: UUID? = null,
        participantId: String? = null,
    ): List<UUID> {
        require(batchSize > 0)
        require((studyId == null) == (participantId == null))
        require(destination in setOf("chronicle_usage_events", "sensor_data", "android_sensor_data"))
        val uploadType = when (destination) {
            "chronicle_usage_events" -> "Android"
            "sensor_data" -> "Ios"
            else -> "AndroidSensor"
        }
        val studies = connection.prepareStatement(
            """
            SELECT DISTINCT study_id FROM (
                SELECT study_id FROM upload_buffer
                WHERE upload_type = ?
                  ${if (studyId == null) "" else "AND study_id = ? AND participant_id = ?"}
                  AND chronicle_participant_mutation_allowed(study_id, participant_id)
                ORDER BY uploaded_at, ctid
                LIMIT ?
            ) candidates
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, uploadType)
            if (studyId != null) {
                statement.setObject(2, studyId)
                statement.setString(3, participantId)
                statement.setInt(4, batchSize)
            } else {
                statement.setInt(2, batchSize)
            }
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(rows.getObject(1, UUID::class.java))
            } }
        }
        // The study fence precedes every row/table write lock, including the buffer claim.
        DeletionStudyFence.shared(connection, studies)
        connection.createStatement().use { statement ->
            listOf("upload_buffer", destination, "participant_stats").sortedWith(comparator)
                .forEach { statement.execute("LOCK TABLE public.$it IN ROW EXCLUSIVE MODE") }
        }
        return studies
    }
}
