package com.openlattice.chronicle.services.upload

import com.geekbeast.util.StopWatch
import com.openlattice.chronicle.collection.AndroidConnectivityStateEvent
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.sql.Types
import java.util.UUID

/**
 * Persists [AndroidConnectivityStateEvent] samples uploaded by the Android
 * `connectivity_state` collection module into the per-row
 * [ChroniclePostgresTables.CONNECTIVITY_STATE_EVENTS] table, scoped to a study + participant.
 *
 * Each event becomes one row; [AndroidConnectivityStateEvent.id] is the per-event
 * de-duplication key (`ON CONFLICT DO NOTHING`), making re-uploads idempotent
 * (mirrors [InteractionEventsUploadService]).
 *
 * DEVICE_STATE_METADATA-class data — transport + metered/validated flags only; no SSID/BSSID/
 * IP/cell identifiers.
 */
public open class ConnectivityStateEventsUploadService(
    private val storageResolver: StorageResolver,
) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(ConnectivityStateEventsUploadService::class.java)

        private val INSERT_CONNECTIVITY_EVENTS_SQL = """
            INSERT INTO ${ChroniclePostgresTables.CONNECTIVITY_STATE_EVENTS.name} (
                study_id, participant_id, event_id, sample_timestamp, timezone,
                event_type, transport, connected, metered, validated, uploaded_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (study_id, participant_id, event_id) DO NOTHING
        """.trimIndent()
    }

    // reason: nesting is the StopWatch.use/connection.use/prepareStatement.use/forEach batched-insert idiom
    @Suppress("NestedBlockDepth")
    public fun upload(
        studyId: UUID,
        participantId: String,
        data: List<AndroidConnectivityStateEvent>,
    ): Int {
        if (data.isEmpty()) {
            return 0
        }
        require(data.size <= 10_000) {
            "Connectivity state events upload batch too large: ${data.size} events (max 10,000)"
        }

        StopWatch(
            log = "Writing ${data.size} connectivity state events for studyId = $studyId, participantId = $participantId",
            level = Level.INFO,
            logger = logger,
        ).use {
            storageResolver.getPlatformStorage().connection.use { connection ->
                connection.prepareStatement(INSERT_CONNECTIVITY_EVENTS_SQL).use { ps ->
                    data.forEach { event ->
                        ps.setObject(1, studyId)
                        ps.setString(2, participantId)
                        ps.setString(3, event.id)
                        ps.setObject(4, event.timestamp)
                        ps.setString(5, event.timezone)
                        ps.setString(6, event.eventType.name)
                        ps.setString(7, event.transport.name)
                        ps.setBoolean(8, event.connected)
                        val metered = event.metered
                        if (metered != null) ps.setBoolean(9, metered) else ps.setNull(9, Types.BOOLEAN)
                        val validated = event.validated
                        if (validated != null) ps.setBoolean(10, validated) else ps.setNull(10, Types.BOOLEAN)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }

        return data.size
    }
}
