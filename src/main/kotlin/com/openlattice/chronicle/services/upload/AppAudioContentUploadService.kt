package com.openlattice.chronicle.services.upload

import com.geekbeast.util.StopWatch
import com.openlattice.chronicle.collection.AndroidAudioContentEvent
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.sql.Types
import java.util.UUID

/**
 * Persists [AndroidAudioContentEvent] media-metadata samples uploaded by the Android
 * `audio_content` collection module into the per-row
 * [ChroniclePostgresTables.APP_AUDIO_CONTENT] table, scoped to a study + participant.
 *
 * Each event becomes one row. [AndroidAudioContentEvent.id] is the per-event de-duplication
 * key, so the insert is `ON CONFLICT (study_id, participant_id, event_id) DO NOTHING`,
 * making re-uploads idempotent (mirrors [InteractionEventsUploadService]).
 *
 * MEDIA_CONTENT-class data — *what* the participant is playing (track title/artist/album the
 * producing app published); still mic-free (no audio waveform).
 */
public open class AppAudioContentUploadService(
    private val storageResolver: StorageResolver,
) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(AppAudioContentUploadService::class.java)

        // Matches the APP_AUDIO_CONTENT PostgresTableDefinition column order.
        private val INSERT_APP_AUDIO_CONTENT_SQL = """
            INSERT INTO ${ChroniclePostgresTables.APP_AUDIO_CONTENT.name} (
                study_id, participant_id, event_id, sample_timestamp, timezone,
                audio_package, title, artist, album, duration_millis, position_millis,
                uploaded_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (study_id, participant_id, event_id) DO NOTHING
        """.trimIndent()
    }

    /**
     * Writes [data] to the app_audio_content table.
     *
     * @return the number of events submitted (not the number of new rows; duplicates are
     *   silently skipped by the ON CONFLICT clause, consistent with the other collection
     *   upload services which return the submitted batch size).
     */
    // reason: nesting is the inherent StopWatch.use/connection.use/prepareStatement.use JDBC
    // batch-insert resource scaffolding
    @Suppress("NestedBlockDepth")
    public fun upload(
        studyId: UUID,
        participantId: String,
        data: List<AndroidAudioContentEvent>,
    ): Int {
        if (data.isEmpty()) {
            return 0
        }
        require(data.size <= 10_000) {
            "Audio content upload batch too large: ${data.size} events (max 10,000)"
        }

        StopWatch(
            log = "Writing ${data.size} audio content events for studyId = $studyId, participantId = $participantId",
            level = Level.INFO,
            logger = logger,
        ).use {
            storageResolver.getPlatformStorage().connection.use { connection ->
                connection.prepareStatement(INSERT_APP_AUDIO_CONTENT_SQL).use { ps ->
                    data.forEach { event ->
                        ps.setObject(1, studyId)
                        ps.setString(2, participantId)
                        ps.setString(3, event.id)
                        ps.setObject(4, event.timestamp)
                        ps.setString(5, event.timezone)
                        ps.setString(6, event.audioPackage)
                        ps.setString(7, event.title)
                        ps.setString(8, event.artist)
                        ps.setString(9, event.album)
                        val durationMillis = event.durationMillis
                        if (durationMillis != null) ps.setLong(10, durationMillis) else ps.setNull(10, Types.BIGINT)
                        val positionMillis = event.positionMillis
                        if (positionMillis != null) ps.setLong(11, positionMillis) else ps.setNull(11, Types.BIGINT)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }

        return data.size
    }
}
