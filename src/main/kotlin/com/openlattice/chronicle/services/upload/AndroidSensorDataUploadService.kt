package com.openlattice.chronicle.services.upload

import com.fasterxml.jackson.databind.ObjectMapper
import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.util.StopWatch
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.storage.AndroidSensorDataWriter
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.util.LogSanitizer
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.*

public open class AndroidSensorDataUploadService(
    private val storageResolver: StorageResolver,
) {

    internal companion object {
        private const val MAX_SENSOR_VALUES = 16
        private val logger = LoggerFactory.getLogger(AndroidSensorDataUploadService::class.java)
        internal val mapper: ObjectMapper = ObjectMappers.newJsonMapper()
    }

    public fun upload(
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        data: List<AndroidSensorSample>
    ): Int {
        require(data.size <= 10_000) { "Android sensor upload batch too large: ${data.size} samples (max 10,000)" }
        require(data.all { it.values.size <= MAX_SENSOR_VALUES }) {
            "Android sensor sample contains too many values (max $MAX_SENSOR_VALUES)"
        }
        val participantRef = LogSanitizer.stableFingerprint(participantId, "participant")
        StopWatch(
            log = "Writing ${data.size} android sensor samples to storage for " +
                "studyId = {}, participantRef = {}, deviceId = {}",
            level = Level.INFO,
            logger = logger,
            studyId,
            participantRef,
            deviceId,
        ).use {
            try {
                storageResolver.getPlatformStorage().connection.use { connection ->
                    val previousAutoCommit = connection.autoCommit
                    connection.autoCommit = false
                    try {
                        val inserted = AndroidSensorDataWriter.write(
                            connection,
                            studyId,
                            participantId,
                            deviceId,
                            data,
                        )
                        // Ack only after final-table data is durable. Retried sample ids are intentionally ignored.
                        connection.commit()
                        logger.info(
                            "Committed android sensor upload - studyId = {}, participantRef = {}, " +
                                "acceptedSamples = {}, insertedSamples = {}",
                            studyId,
                            participantRef,
                            data.size,
                            inserted,
                        )
                    } catch (ex: Exception) {
                        try {
                            connection.rollback()
                        } catch (rollbackFailure: Exception) {
                            ex.addSuppressed(rollbackFailure)
                        }
                        throw ex
                    } finally {
                        try {
                            connection.autoCommit = previousAutoCommit
                        } catch (restoreFailure: Exception) {
                            logger.warn("Unable to restore sensor-upload connection auto-commit", restoreFailure)
                        }
                    }
                }
                ChronicleMetrics.uploadTotal.labels("android_sensor").inc()
            } catch (ex: Exception) {
                ChronicleMetrics.uploadErrors.labels("android_sensor", ex.javaClass.simpleName).inc()
                throw ex
            }
        }

        return data.size
    }
}
