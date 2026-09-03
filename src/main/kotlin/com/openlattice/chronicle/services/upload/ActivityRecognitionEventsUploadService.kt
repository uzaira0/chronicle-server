package com.openlattice.chronicle.services.upload

import com.geekbeast.util.StopWatch
import com.openlattice.chronicle.collection.AndroidActivityRecognitionEvent
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.sql.PreparedStatement
import java.util.UUID

/**
 * Persists [AndroidActivityRecognitionEvent] samples uploaded by the Android
 * `activity_recognition` collection module into the per-row
 * [ChroniclePostgresTables.ACTIVITY_RECOGNITION_EVENTS] table, scoped to a study + participant.
 *
 * Each event becomes one row; [AndroidActivityRecognitionEvent.id] is the per-event
 * de-duplication key (`ON CONFLICT DO NOTHING`), making re-uploads idempotent
 * (mirrors [InteractionEventsUploadService]).
 *
 * BEHAVIORAL_METADATA-class data — content-free; only an activity label + confidence.
 */
public open class ActivityRecognitionEventsUploadService(
    private val storageResolver: StorageResolver,
) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(ActivityRecognitionEventsUploadService::class.java)

        private val INSERT_ACTIVITY_EVENTS_SQL = """
            INSERT INTO ${ChroniclePostgresTables.ACTIVITY_RECOGNITION_EVENTS.name} (
                study_id, participant_id, event_id, sample_timestamp, timezone,
                activity_type, confidence, transition_type, uploaded_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (study_id, participant_id, event_id) DO NOTHING
        """.trimIndent()
    }

    // reason: nesting is the StopWatch.use/connection.use/prepareStatement.use JDBC resource idiom
    @Suppress("NestedBlockDepth")
    public fun upload(
        studyId: UUID,
        participantId: String,
        data: List<AndroidActivityRecognitionEvent>,
    ): Int {
        if (data.isEmpty()) {
            return 0
        }
        require(data.size <= 10_000) {
            "Activity recognition events upload batch too large: ${data.size} events (max 10,000)"
        }

        StopWatch(
            log = "Writing ${data.size} activity recognition events for studyId = $studyId, participantId = $participantId",
            level = Level.INFO,
            logger = logger,
        ).use {
            storageResolver.getPlatformStorage().connection.use { connection ->
                connection.prepareStatement(INSERT_ACTIVITY_EVENTS_SQL).use { ps ->
                    data.forEach { event -> bindEvent(ps, studyId, participantId, event) }
                    ps.executeBatch()
                }
            }
        }

        return data.size
    }

    private fun bindEvent(
        ps: PreparedStatement,
        studyId: UUID,
        participantId: String,
        event: AndroidActivityRecognitionEvent,
    ) {
        ps.setObject(1, studyId)
        ps.setString(2, participantId)
        ps.setString(3, event.id)
        ps.setObject(4, event.timestamp)
        ps.setString(5, event.timezone)
        ps.setString(6, event.activityType.name)
        ps.setInt(7, event.confidence)
        ps.setString(8, event.transitionType?.name)
        ps.addBatch()
    }
}
