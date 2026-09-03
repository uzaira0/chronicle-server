package com.openlattice.chronicle.services.upload

import com.geekbeast.util.StopWatch
import com.openlattice.chronicle.collection.AndroidAudioActivityEvent
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.sql.Types
import java.util.UUID

/**
 * Persists [AndroidAudioActivityEvent] app-audio-activity samples uploaded by the Android
 * `audio_activity` collection module into the per-row
 * [ChroniclePostgresTables.APP_AUDIO_ACTIVITY] table, scoped to a study + participant.
 *
 * Each event becomes one row. [AndroidAudioActivityEvent.id] is the per-event de-duplication
 * key, so the insert is `ON CONFLICT (study_id, participant_id, event_id) DO NOTHING`,
 * making re-uploads idempotent (mirrors [InteractionEventsUploadService]).
 *
 * BEHAVIORAL_METADATA-class data — mic-free by construction; the persisted columns carry the
 * device's own playback/output state, never an audio waveform.
 */
public open class AppAudioActivityUploadService(
    private val storageResolver: StorageResolver,
) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(AppAudioActivityUploadService::class.java)

        // Matches the APP_AUDIO_ACTIVITY PostgresTableDefinition column order.
        private val INSERT_APP_AUDIO_ACTIVITY_SQL = """
            INSERT INTO ${ChroniclePostgresTables.APP_AUDIO_ACTIVITY.name} (
                study_id, participant_id, event_id, sample_timestamp, timezone,
                event_type, audio_active, audio_package, content_type, playback_state,
                output_route, route_connected, media_volume, max_media_volume, ringer_mode,
                dnd_active, call_active,
                uploaded_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (study_id, participant_id, event_id) DO NOTHING
        """.trimIndent()
    }

    /**
     * Writes [data] to the app_audio_activity table.
     *
     * @return the number of events submitted (not the number of new rows; duplicates are
     *   silently skipped by the ON CONFLICT clause, consistent with the other collection
     *   upload services which return the submitted batch size).
     */
    // reason: per-column nullable bind logic over a batched prepared statement inside StopWatch +
    // JDBC use{} resource scopes; the nesting is inherent to the connection/statement lifecycle and
    // extracting it would break resource management
    @Suppress("NestedBlockDepth")
    public fun upload(
        studyId: UUID,
        participantId: String,
        data: List<AndroidAudioActivityEvent>,
    ): Int {
        if (data.isEmpty()) {
            return 0
        }
        require(data.size <= 10_000) {
            "Audio activity upload batch too large: ${data.size} events (max 10,000)"
        }

        StopWatch(
            log = "Writing ${data.size} audio activity events for studyId = $studyId, participantId = $participantId",
            level = Level.INFO,
            logger = logger,
        ).use {
            storageResolver.getPlatformStorage().connection.use { connection ->
                connection.prepareStatement(INSERT_APP_AUDIO_ACTIVITY_SQL).use { ps ->
                    data.forEach { event ->
                        ps.setObject(1, studyId)
                        ps.setString(2, participantId)
                        ps.setString(3, event.id)
                        ps.setObject(4, event.timestamp)
                        ps.setString(5, event.timezone)
                        ps.setString(6, event.eventType.name)
                        ps.setBoolean(7, event.audioActive)
                        ps.setString(8, event.audioPackage)
                        ps.setString(9, event.contentType?.name)
                        ps.setString(10, event.playbackState?.name)
                        ps.setString(11, event.outputRoute?.name)
                        val routeConnected = event.routeConnected
                        if (routeConnected != null) ps.setBoolean(12, routeConnected) else ps.setNull(12, Types.BOOLEAN)
                        val mediaVolume = event.mediaVolume
                        if (mediaVolume != null) ps.setInt(13, mediaVolume) else ps.setNull(13, Types.INTEGER)
                        val maxMediaVolume = event.maxMediaVolume
                        if (maxMediaVolume != null) ps.setInt(14, maxMediaVolume) else ps.setNull(14, Types.INTEGER)
                        ps.setString(15, event.ringerMode?.name)
                        val dndActive = event.dndActive
                        if (dndActive != null) ps.setBoolean(16, dndActive) else ps.setNull(16, Types.BOOLEAN)
                        val callActive = event.callActive
                        if (callActive != null) ps.setBoolean(17, callActive) else ps.setNull(17, Types.BOOLEAN)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }

        return data.size
    }
}
