package com.openlattice.chronicle.services.upload

import com.geekbeast.util.StopWatch
import com.openlattice.chronicle.collection.AndroidNotificationActivityEvent
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.sql.Types
import java.util.UUID

/**
 * Persists [AndroidNotificationActivityEvent] notification-activity samples uploaded by the
 * Android `notification_activity` collection module into the per-row
 * [ChroniclePostgresTables.NOTIFICATION_ACTIVITY] table, scoped to a study + participant.
 *
 * Each event becomes one row. [AndroidNotificationActivityEvent.id] is the per-event
 * de-duplication key, so the insert is `ON CONFLICT (study_id, participant_id, event_id)
 * DO NOTHING`, making re-uploads idempotent (mirrors [InteractionEventsUploadService]).
 *
 * BEHAVIORAL_METADATA-class data — content-free by construction; the persisted columns carry
 * the posting package + Android category constant + posted/removed, never the notification's
 * title or text.
 */
public open class NotificationActivityUploadService(
    private val storageResolver: StorageResolver,
) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(NotificationActivityUploadService::class.java)

        // Matches the NOTIFICATION_ACTIVITY PostgresTableDefinition column order.
        private val INSERT_NOTIFICATION_ACTIVITY_SQL = """
            INSERT INTO ${ChroniclePostgresTables.NOTIFICATION_ACTIVITY.name} (
                study_id, participant_id, event_id, sample_timestamp, timezone,
                event_type, package_name, category, ongoing, importance,
                uploaded_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (study_id, participant_id, event_id) DO NOTHING
        """.trimIndent()
    }

    /**
     * Writes [data] to the notification_activity table.
     *
     * @return the number of events submitted (not the number of new rows; duplicates are
     *   silently skipped by the ON CONFLICT clause, consistent with the other collection
     *   upload services which return the submitted batch size).
     */
    // reason: StopWatch/connection/prepared-statement use{} blocks plus the per-event batch loop are
    // inherent JDBC nesting; extracting would not reduce the resource-management depth
    @Suppress("NestedBlockDepth")
    public fun upload(
        studyId: UUID,
        participantId: String,
        data: List<AndroidNotificationActivityEvent>,
    ): Int {
        if (data.isEmpty()) {
            return 0
        }
        require(data.size <= 10_000) {
            "Notification activity upload batch too large: ${data.size} events (max 10,000)"
        }

        StopWatch(
            log = "Writing ${data.size} notification activity events for studyId = $studyId, participantId = $participantId",
            level = Level.INFO,
            logger = logger,
        ).use {
            storageResolver.getPlatformStorage().connection.use { connection ->
                connection.prepareStatement(INSERT_NOTIFICATION_ACTIVITY_SQL).use { ps ->
                    data.forEach { event ->
                        ps.setObject(1, studyId)
                        ps.setString(2, participantId)
                        ps.setString(3, event.id)
                        ps.setObject(4, event.timestamp)
                        ps.setString(5, event.timezone)
                        ps.setString(6, event.eventType.name)
                        ps.setString(7, event.packageName)
                        ps.setString(8, event.category)
                        val ongoing = event.ongoing
                        if (ongoing != null) ps.setBoolean(9, ongoing) else ps.setNull(9, Types.BOOLEAN)
                        val importance = event.importance
                        if (importance != null) ps.setInt(10, importance) else ps.setNull(10, Types.INTEGER)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }

        return data.size
    }
}
