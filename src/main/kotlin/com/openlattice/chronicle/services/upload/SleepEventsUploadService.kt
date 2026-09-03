package com.openlattice.chronicle.services.upload

import com.geekbeast.util.StopWatch
import com.openlattice.chronicle.collection.AndroidSleepEvent
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.sql.PreparedStatement
import java.sql.Types
import java.util.UUID

/**
 * Persists [AndroidSleepEvent] sleep samples uploaded by the Android `sleep` collection
 * module into the per-row [ChroniclePostgresTables.SLEEP_EVENTS] table, scoped to a
 * study + participant.
 *
 * Each event becomes one row. [AndroidSleepEvent.id] is the per-event de-duplication key, so
 * the insert is `ON CONFLICT (study_id, participant_id, event_id) DO NOTHING`, making
 * re-uploads idempotent (mirrors [InteractionEventsUploadService]).
 *
 * HEALTH_METRICS-class data — content-free/mic-free by construction; the persisted columns
 * carry a sleep label/confidence and coarse light/motion levels only.
 */
public open class SleepEventsUploadService(
    private val storageResolver: StorageResolver,
) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(SleepEventsUploadService::class.java)

        // Matches the SLEEP_EVENTS PostgresTableDefinition column order.
        private val INSERT_SLEEP_EVENTS_SQL = """
            INSERT INTO ${ChroniclePostgresTables.SLEEP_EVENTS.name} (
                study_id, participant_id, event_id, sample_timestamp, timezone,
                event_type, segment_start_millis, segment_end_millis, segment_status,
                confidence, light, motion, uploaded_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (study_id, participant_id, event_id) DO NOTHING
        """.trimIndent()
    }

    /**
     * Writes [data] to the sleep_events table.
     *
     * @return the number of events submitted (duplicates are silently skipped by the
     *   ON CONFLICT clause, consistent with the other collection upload services).
     */
    // reason: nesting is the StopWatch.use/connection.use/prepareStatement.use JDBC resource idiom
    @Suppress("NestedBlockDepth")
    public fun upload(
        studyId: UUID,
        participantId: String,
        data: List<AndroidSleepEvent>,
    ): Int {
        if (data.isEmpty()) {
            return 0
        }
        require(data.size <= 10_000) {
            "Sleep events upload batch too large: ${data.size} events (max 10,000)"
        }

        StopWatch(
            log = "Writing ${data.size} sleep events for studyId = $studyId, participantId = $participantId",
            level = Level.INFO,
            logger = logger,
        ).use {
            storageResolver.getPlatformStorage().connection.use { connection ->
                connection.prepareStatement(INSERT_SLEEP_EVENTS_SQL).use { ps ->
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
        event: AndroidSleepEvent,
    ) {
        ps.setObject(1, studyId)
        ps.setString(2, participantId)
        ps.setString(3, event.id)
        ps.setObject(4, event.timestamp)
        ps.setString(5, event.timezone)
        ps.setString(6, event.eventType.name)
        val segStart = event.segmentStartMillis
        if (segStart != null) ps.setLong(7, segStart) else ps.setNull(7, Types.BIGINT)
        val segEnd = event.segmentEndMillis
        if (segEnd != null) ps.setLong(8, segEnd) else ps.setNull(8, Types.BIGINT)
        ps.setString(9, event.segmentStatus?.name)
        val confidence = event.confidence
        if (confidence != null) ps.setInt(10, confidence) else ps.setNull(10, Types.INTEGER)
        val light = event.light
        if (light != null) ps.setInt(11, light) else ps.setNull(11, Types.INTEGER)
        val motion = event.motion
        if (motion != null) ps.setInt(12, motion) else ps.setNull(12, Types.INTEGER)
        ps.addBatch()
    }
}
