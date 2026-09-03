package com.openlattice.chronicle.services.upload

import com.geekbeast.util.StopWatch
import com.openlattice.chronicle.collection.AmbientAudioClassificationEvent
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.sql.Types
import java.util.UUID

/**
 * Persists [AmbientAudioClassificationEvent] events uploaded by the `ambient_audio` collection
 * module (currently iOS SoundAnalysis) into the per-row
 * [ChroniclePostgresTables.AMBIENT_AUDIO_EVENTS] table, scoped to a study + participant.
 *
 * Each event becomes one row. [AmbientAudioClassificationEvent.id] is the per-event
 * de-duplication key, so the insert is `ON CONFLICT (study_id, participant_id, event_id)
 * DO NOTHING`, making re-uploads idempotent (mirrors [AppAudioActivityUploadService]).
 *
 * AMBIENT_AUDIO_CONTEXT-class data — labels-only by construction: the persisted columns carry
 * an on-device sound-class label + confidence and the listen-window bounds; no audio
 * representation exists past the device-side classifier.
 */
public open class AmbientAudioUploadService(
    private val storageResolver: StorageResolver,
) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(AmbientAudioUploadService::class.java)

        // Matches the AMBIENT_AUDIO_EVENTS PostgresTableDefinition column order.
        private val INSERT_AMBIENT_AUDIO_SQL = """
            INSERT INTO ${ChroniclePostgresTables.AMBIENT_AUDIO_EVENTS.name} (
                study_id, participant_id, event_id, sample_timestamp, timezone,
                window_start_millis, window_end_millis, label, confidence, classifier_version,
                uploaded_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (study_id, participant_id, event_id) DO NOTHING
        """.trimIndent()
    }

    /**
     * Writes [data] to the ambient_audio_events table.
     *
     * @return the number of events submitted (not the number of new rows; duplicates are
     *   silently skipped by the ON CONFLICT clause, consistent with the other collection
     *   upload services which return the submitted batch size).
     */
    public fun upload(
        studyId: UUID,
        participantId: String,
        data: List<AmbientAudioClassificationEvent>,
    ): Int {
        if (data.isEmpty()) {
            return 0
        }
        require(data.size <= 10_000) {
            "Ambient audio upload batch too large: ${data.size} events (max 10,000)"
        }

        StopWatch(
            log = "Writing ${data.size} ambient audio events for studyId = $studyId, participantId = $participantId",
            level = Level.INFO,
            logger = logger,
        ).use {
            storageResolver.getPlatformStorage().connection.use { connection ->
                connection.prepareStatement(INSERT_AMBIENT_AUDIO_SQL).use { ps ->
                    data.forEach { event ->
                        ps.setObject(1, studyId)
                        ps.setString(2, participantId)
                        ps.setString(3, event.id)
                        ps.setObject(4, event.timestamp)
                        ps.setString(5, event.timezone)
                        ps.setLong(6, event.windowStartMillis)
                        ps.setLong(7, event.windowEndMillis)
                        ps.setString(8, event.label)
                        ps.setDouble(9, event.confidence)
                        val classifierVersion = event.classifierVersion
                        if (classifierVersion != null) {
                            ps.setString(10, classifierVersion)
                        } else {
                            ps.setNull(10, Types.VARCHAR)
                        }
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }

        return data.size
    }
}
