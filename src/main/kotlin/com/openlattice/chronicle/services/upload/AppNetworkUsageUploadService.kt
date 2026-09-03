package com.openlattice.chronicle.services.upload

import com.geekbeast.util.StopWatch
import com.openlattice.chronicle.collection.AndroidAppNetworkUsageEvent
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.UUID

/**
 * Persists [AndroidAppNetworkUsageEvent] samples uploaded by the Android `app_network_usage`
 * collection module into the per-row [ChroniclePostgresTables.APP_NETWORK_USAGE] table, scoped
 * to a study + participant.
 *
 * Each bucket becomes one row; [AndroidAppNetworkUsageEvent.id] is the per-event de-duplication
 * key (`ON CONFLICT DO NOTHING`), making re-uploads idempotent
 * (mirrors [InteractionEventsUploadService]).
 *
 * BEHAVIORAL_METADATA-class data — per-app byte counts only; zero payload/destination/domain/
 * URL visibility.
 */
public open class AppNetworkUsageUploadService(
    private val storageResolver: StorageResolver,
) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(AppNetworkUsageUploadService::class.java)

        private val INSERT_APP_NETWORK_USAGE_SQL = """
            INSERT INTO ${ChroniclePostgresTables.APP_NETWORK_USAGE.name} (
                study_id, participant_id, event_id, sample_timestamp, timezone,
                package_name, network_type, rx_bytes, tx_bytes,
                bucket_start_millis, bucket_end_millis, uploaded_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (study_id, participant_id, event_id) DO NOTHING
        """.trimIndent()
    }

    public fun upload(
        studyId: UUID,
        participantId: String,
        data: List<AndroidAppNetworkUsageEvent>,
    ): Int {
        if (data.isEmpty()) {
            return 0
        }
        require(data.size <= 10_000) {
            "App network usage upload batch too large: ${data.size} events (max 10,000)"
        }

        StopWatch(
            log = "Writing ${data.size} app network usage events for studyId = $studyId, participantId = $participantId",
            level = Level.INFO,
            logger = logger,
        ).use {
            persistEvents(studyId, participantId, data)
        }

        return data.size
    }

    private fun persistEvents(
        studyId: UUID,
        participantId: String,
        data: List<AndroidAppNetworkUsageEvent>,
    ) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(INSERT_APP_NETWORK_USAGE_SQL).use { ps ->
                data.forEach { event ->
                    ps.setObject(1, studyId)
                    ps.setString(2, participantId)
                    ps.setString(3, event.id)
                    ps.setObject(4, event.timestamp)
                    ps.setString(5, event.timezone)
                    ps.setString(6, event.packageName)
                    ps.setString(7, event.networkType.name)
                    ps.setLong(8, event.rxBytes)
                    ps.setLong(9, event.txBytes)
                    ps.setLong(10, event.bucketStartMillis)
                    ps.setLong(11, event.bucketEndMillis)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }
}
