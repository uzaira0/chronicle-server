package com.openlattice.chronicle.services.upload

import com.geekbeast.util.StopWatch
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.collection.IosBatterySample
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.UUID

/**
 * Persists [BatterySample] telemetry uploaded by the Android `battery_telemetry`
 * collection module into the per-row [ChroniclePostgresTables.BATTERY_TELEMETRY] table,
 * scoped to a study + participant.
 *
 * Each sample becomes one row. [BatterySample.id] is the per-sample de-duplication key,
 * so the insert is `ON CONFLICT (study_id, participant_id, sample_id) DO NOTHING`,
 * making re-uploads idempotent (mirrors how the Android client may retry batches).
 */
public open class BatteryTelemetryUploadService(
    private val storageResolver: StorageResolver,
) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(BatteryTelemetryUploadService::class.java)

        // Matches the BATTERY_TELEMETRY PostgresTableDefinition column order.
        private val INSERT_BATTERY_TELEMETRY_SQL = """
            INSERT INTO ${ChroniclePostgresTables.BATTERY_TELEMETRY.name} (
                study_id, participant_id, sample_id, sample_timestamp, timezone,
                level_percent, charging_state, plug_type, health,
                temperature_deci_c, voltage_millivolts, uploaded_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (study_id, participant_id, sample_id) DO NOTHING
        """.trimIndent()

        // iOS samples carry no plug type, health, temperature, or voltage (stored as
        // NULL — see IosBatterySample), and add the iOS-only low_power_mode flag.
        private val INSERT_IOS_BATTERY_TELEMETRY_SQL = """
            INSERT INTO ${ChroniclePostgresTables.BATTERY_TELEMETRY.name} (
                study_id, participant_id, sample_id, sample_timestamp, timezone,
                level_percent, charging_state, low_power_mode, uploaded_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (study_id, participant_id, sample_id) DO NOTHING
        """.trimIndent()
    }

    /**
     * Writes [data] to the battery_telemetry table.
     *
     * @return the number of samples submitted (not the number of new rows; duplicates
     *   are silently skipped by the ON CONFLICT clause, consistent with the other
     *   collection upload services which return the submitted batch size).
     */
    // reason: nesting is the StopWatch.use/connection.use/prepareStatement.use/forEach batched-insert idiom
    @Suppress("NestedBlockDepth")
    public fun upload(
        studyId: UUID,
        participantId: String,
        data: List<BatterySample>,
    ): Int {
        if (data.isEmpty()) {
            return 0
        }
        require(data.size <= 10_000) {
            "Battery telemetry upload batch too large: ${data.size} samples (max 10,000)"
        }

        StopWatch(
            log = "Writing ${data.size} battery telemetry samples for studyId = $studyId, participantId = $participantId",
            level = Level.INFO,
            logger = logger,
        ).use {
            storageResolver.getPlatformStorage().connection.use { connection ->
                connection.prepareStatement(INSERT_BATTERY_TELEMETRY_SQL).use { ps ->
                    data.forEach { sample ->
                        ps.setObject(1, studyId)
                        ps.setString(2, participantId)
                        ps.setString(3, sample.id)
                        ps.setObject(4, sample.timestamp)
                        ps.setString(5, sample.timezone)
                        ps.setInt(6, sample.levelPercent)
                        ps.setString(7, sample.chargingState.name)
                        ps.setString(8, sample.plugType.name)
                        ps.setString(9, sample.health.name)
                        ps.setInt(10, sample.temperatureDeciC)
                        ps.setInt(11, sample.voltageMillivolts)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }

        return data.size
    }

    /**
     * Writes iOS [data] to the same battery_telemetry table. Same idempotency and
     * return-value semantics as [upload]; the Android-hardware columns stay NULL.
     */
    // reason: nesting is the StopWatch.use/connection.use/prepareStatement.use/forEach batched-insert idiom
    @Suppress("NestedBlockDepth")
    public fun uploadIos(
        studyId: UUID,
        participantId: String,
        data: List<IosBatterySample>,
    ): Int {
        if (data.isEmpty()) {
            return 0
        }
        require(data.size <= 10_000) {
            "Battery telemetry upload batch too large: ${data.size} samples (max 10,000)"
        }

        StopWatch(
            log = "Writing ${data.size} iOS battery telemetry samples for studyId = $studyId, participantId = $participantId",
            level = Level.INFO,
            logger = logger,
        ).use {
            storageResolver.getPlatformStorage().connection.use { connection ->
                connection.prepareStatement(INSERT_IOS_BATTERY_TELEMETRY_SQL).use { ps ->
                    data.forEach { sample ->
                        ps.setObject(1, studyId)
                        ps.setString(2, participantId)
                        ps.setString(3, sample.id)
                        ps.setObject(4, sample.timestamp)
                        ps.setString(5, sample.timezone)
                        ps.setInt(6, sample.levelPercent)
                        ps.setString(7, sample.chargingState.name)
                        ps.setObject(8, sample.lowPowerMode)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            }
        }

        return data.size
    }
}
