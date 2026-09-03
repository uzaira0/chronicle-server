package com.openlattice.chronicle.services.upload

import com.geekbeast.util.StopWatch
import com.openlattice.chronicle.collection.AndroidInteractionEvent
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.sql.PreparedStatement
import java.sql.Types
import java.util.UUID

/**
 * Persists [AndroidInteractionEvent] interaction-salience events uploaded by the Android
 * `interaction_events` collection module into the per-row
 * [ChroniclePostgresTables.INTERACTION_EVENTS] table, scoped to a study + participant.
 *
 * Each event becomes one row. [AndroidInteractionEvent.id] is the per-event de-duplication
 * key, so the insert is `ON CONFLICT (study_id, participant_id, event_id) DO NOTHING`,
 * making re-uploads idempotent (mirrors how the Android client may retry batches, and
 * mirrors [BatteryTelemetryUploadService]).
 *
 * INTERACTION_METADATA-class data — content-free by construction; the persisted columns
 * carry no element text.
 */
public open class InteractionEventsUploadService(
    private val storageResolver: StorageResolver,
) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(InteractionEventsUploadService::class.java)

        // Matches the INTERACTION_EVENTS PostgresTableDefinition column order.
        private val INSERT_INTERACTION_EVENTS_SQL = """
            INSERT INTO ${ChroniclePostgresTables.INTERACTION_EVENTS.name} (
                study_id, participant_id, event_id, sample_timestamp, timezone,
                event_type, grid_rows, grid_cols, grid_row, grid_col,
                element_role, foreground_package,
                position_source, node_bounds_left, node_bounds_top, node_bounds_right,
                node_bounds_bottom, display_id,
                raw_x, raw_y, screen_width, screen_height,
                normalized_x, normalized_y, scroll_delta_x, scroll_delta_y,
                event_time_millis, episode_id, dwell_millis_since_prev, orientation,
                screen_density_dpi, scroll_velocity_x, scroll_velocity_y, scroll_reversed,
                uploaded_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (study_id, participant_id, event_id) DO NOTHING
        """.trimIndent()
    }

    /**
     * Writes [data] to the interaction_events table.
     *
     * @return the number of events submitted (not the number of new rows; duplicates are
     *   silently skipped by the ON CONFLICT clause, consistent with the other collection
     *   upload services which return the submitted batch size).
     */
    // reason: nesting is the StopWatch.use/connection.use/prepareStatement.use JDBC resource idiom
    @Suppress("NestedBlockDepth")
    public fun upload(
        studyId: UUID,
        participantId: String,
        data: List<AndroidInteractionEvent>,
    ): Int {
        if (data.isEmpty()) {
            return 0
        }
        require(data.size <= 10_000) {
            "Interaction events upload batch too large: ${data.size} events (max 10,000)"
        }

        StopWatch(
            log = "Writing ${data.size} interaction events for studyId = $studyId, participantId = $participantId",
            level = Level.INFO,
            logger = logger,
        ).use {
            storageResolver.getPlatformStorage().connection.use { connection ->
                connection.prepareStatement(INSERT_INTERACTION_EVENTS_SQL).use { ps ->
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
        event: AndroidInteractionEvent,
    ) {
        ps.setObject(1, studyId)
        ps.setString(2, participantId)
        ps.setString(3, event.id)
        ps.setObject(4, event.timestamp)
        ps.setString(5, event.timezone)
        ps.setString(6, event.eventType.name)
        ps.setInt(7, event.gridRows)
        ps.setInt(8, event.gridCols)
        ps.setInt(9, event.gridRow)
        ps.setInt(10, event.gridCol)
        ps.setString(11, event.elementRole)
        ps.setString(12, event.foregroundPackage)
        ps.setString(13, event.positionSource?.name)
        ps.setIntOrNull(14, event.nodeBoundsLeft)
        ps.setIntOrNull(15, event.nodeBoundsTop)
        ps.setIntOrNull(16, event.nodeBoundsRight)
        ps.setIntOrNull(17, event.nodeBoundsBottom)
        ps.setIntOrNull(18, event.displayId)
        ps.setIntOrNull(19, event.rawX)
        ps.setIntOrNull(20, event.rawY)
        ps.setIntOrNull(21, event.screenWidth)
        ps.setIntOrNull(22, event.screenHeight)
        ps.setDoubleOrNull(23, event.normalizedX)
        ps.setDoubleOrNull(24, event.normalizedY)
        ps.setIntOrNull(25, event.scrollDeltaX)
        ps.setIntOrNull(26, event.scrollDeltaY)
        ps.setLongOrNull(27, event.eventTimeMillis)
        ps.setString(28, event.episodeId)
        ps.setLongOrNull(29, event.dwellMillisSincePrev)
        ps.setIntOrNull(30, event.orientation)
        ps.setIntOrNull(31, event.screenDensityDpi)
        ps.setDoubleOrNull(32, event.scrollVelocityX)
        ps.setDoubleOrNull(33, event.scrollVelocityY)
        ps.setBooleanOrNull(34, event.scrollReversed)
        ps.addBatch()
    }

    private fun PreparedStatement.setIntOrNull(index: Int, value: Int?) {
        if (value != null) setInt(index, value) else setNull(index, Types.INTEGER)
    }

    private fun PreparedStatement.setLongOrNull(index: Int, value: Long?) {
        if (value != null) setLong(index, value) else setNull(index, Types.BIGINT)
    }

    private fun PreparedStatement.setDoubleOrNull(index: Int, value: Double?) {
        if (value != null) setDouble(index, value) else setNull(index, Types.DOUBLE)
    }

    private fun PreparedStatement.setBooleanOrNull(index: Int, value: Boolean?) {
        if (value != null) setBoolean(index, value) else setNull(index, Types.BOOLEAN)
    }
}
