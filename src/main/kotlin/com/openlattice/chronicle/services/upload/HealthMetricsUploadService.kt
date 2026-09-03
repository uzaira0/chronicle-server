package com.openlattice.chronicle.services.upload

import com.geekbeast.util.StopWatch
import com.openlattice.chronicle.collection.AndroidHealthMetricEvent
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.UUID

/**
 * Persists [AndroidHealthMetricEvent] records uploaded by the Android `health_connect`
 * collection module into the per-row [ChroniclePostgresTables.HEALTH_METRICS] table, scoped
 * to a study + participant.
 *
 * Each record becomes one row; [AndroidHealthMetricEvent.id] is the per-event de-duplication
 * key (`ON CONFLICT DO NOTHING`), making re-uploads idempotent
 * (mirrors [InteractionEventsUploadService]).
 *
 * HEALTH_METRICS-class data — one aggregated/instantaneous Health Connect record (value +
 * unit interpreted per metric type).
 */
public open class HealthMetricsUploadService(
    private val storageResolver: StorageResolver,
) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(HealthMetricsUploadService::class.java)

        private val INSERT_HEALTH_METRICS_SQL = """
            INSERT INTO ${ChroniclePostgresTables.HEALTH_METRICS.name} (
                study_id, participant_id, event_id, sample_timestamp, timezone,
                metric_type, metric_value, unit, start_millis, end_millis,
                source_package, uploaded_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (study_id, participant_id, event_id) DO NOTHING
        """.trimIndent()
    }

    public fun upload(
        studyId: UUID,
        participantId: String,
        data: List<AndroidHealthMetricEvent>,
    ): Int {
        if (data.isEmpty()) {
            return 0
        }
        require(data.size <= 10_000) {
            "Health metrics upload batch too large: ${data.size} records (max 10,000)"
        }

        StopWatch(
            log = "Writing ${data.size} health metrics for studyId = $studyId, participantId = $participantId",
            level = Level.INFO,
            logger = logger,
        ).use {
            storageResolver.getPlatformStorage().connection.use { connection ->
                connection.prepareStatement(INSERT_HEALTH_METRICS_SQL).use { ps ->
                    batchInsert(ps, studyId, participantId, data)
                }
            }
        }

        return data.size
    }

    private fun batchInsert(
        ps: java.sql.PreparedStatement,
        studyId: UUID,
        participantId: String,
        data: List<AndroidHealthMetricEvent>,
    ) {
        data.forEach { event ->
            ps.setObject(1, studyId)
            ps.setString(2, participantId)
            ps.setString(3, event.id)
            ps.setObject(4, event.timestamp)
            ps.setString(5, event.timezone)
            ps.setString(6, event.metricType.name)
            ps.setDouble(7, event.value)
            ps.setString(8, event.unit)
            ps.setLong(9, event.startMillis)
            ps.setLong(10, event.endMillis)
            ps.setString(11, event.sourcePackage)
            ps.addBatch()
        }
        ps.executeBatch()
    }
}
