package com.openlattice.chronicle.services.upload

import com.geekbeast.util.StopWatch
import com.openlattice.chronicle.postgres.postgresText
import com.openlattice.chronicle.collection.AndroidNotificationActivityEvent
import com.openlattice.chronicle.postgres.bindCollectionEvent
import com.openlattice.chronicle.postgres.setBooleanOrNull
import com.openlattice.chronicle.postgres.setIntOrNull
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.ParticipantPurgeCutoff
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
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
            log = "Writing ${data.size} notification activity events ",
            level = Level.INFO,
            logger = logger,
        ).use {
            ParticipantPurgeCutoff.withPermittedBatch(
                storageResolver.getPlatformStorage(), studyId, participantId, data, { it.timestamp },
            ) { connection, retained ->
                connection.prepareStatement(INSERT_NOTIFICATION_ACTIVITY_SQL).use { ps ->
                    retained.forEach { event ->
                        ps.bindCollectionEvent(studyId, participantId, event.id, event.timestamp, event.timezone)
                        ps.setString(6, event.eventType.name)
                        ps.setString(7, event.packageName.postgresText())
                        ps.setString(8, event.category?.postgresText())
                        ps.setBooleanOrNull(9, event.ongoing)
                        ps.setIntOrNull(10, event.importance)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }

        return data.size
    }
}
