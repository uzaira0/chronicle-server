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
    ): List<UUID> {
        require(batchSize > 0)
        require(destination in setOf("chronicle_usage_events", "android_sensor_data"))
        val uploadType = if (destination == "chronicle_usage_events") "Android" else "AndroidSensor"
        val studies = connection.prepareStatement(
            """
            SELECT DISTINCT study_id FROM (
                SELECT study_id FROM upload_buffer
                WHERE upload_type = ?
                  AND chronicle_participant_mutation_allowed(study_id, participant_id)
                ORDER BY uploaded_at, ctid
                LIMIT ?
            ) candidates
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, uploadType)
            statement.setInt(2, batchSize)
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(rows.getObject(1, UUID::class.java))
            } }
        }
        // The study fence precedes every row/table write lock, including the buffer claim.
        DeletionStudyFence.shared(connection, studies)
        // Serialize drains while allowing unrelated study writes and erasure.
        connection.createStatement().use { statement ->
            listOf("upload_buffer", destination, "participant_stats").sortedWith(comparator)
                .forEach { statement.execute("LOCK TABLE public.$it IN SHARE UPDATE EXCLUSIVE MODE") }
        }
        return studies
    }
}
