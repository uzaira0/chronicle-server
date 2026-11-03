package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.storage.withTransaction
import com.openlattice.chronicle.storage.ParticipantCollectionGate

import com.fasterxml.jackson.databind.ObjectMapper
import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.util.StopWatch
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.storage.AndroidSensorDataWriter
import com.openlattice.chronicle.storage.DeletionStudyFence
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
        randomDeviceId: UUID,
        data: List<AndroidSensorSample>
    ): Int {
        requireUploadBatchSize(data.size)
        requireUploadRow(data.all { it.values.size <= MAX_SENSOR_VALUES }) {
            "Android sensor sample contains too many values (max $MAX_SENSOR_VALUES)"
        }
        val participantRef = LogSanitizer.stableFingerprint(participantId, "participant")
        StopWatch(
            log = "Writing ${data.size} android sensor samples to storage for " +
                "studyRef = {}, participantRef = {}, randomDeviceId = {}",
            level = Level.INFO,
            logger = logger,
            LogSanitizer.stableFingerprint(studyId.toString(), "study"),
            participantRef,
            LogSanitizer.stableFingerprint(randomDeviceId.toString(), "device"),
        ).use {
            try {
                storageResolver.getPlatformStorage().connection.use { connection ->
                    connection.withTransaction {
                        DeletionStudyFence.shared(connection, studyId)
                        ParticipantCollectionGate(connection).requireCollectionAvailable(studyId, participantId)
                        val inserted = AndroidSensorDataWriter.write(
                            connection,
                            studyId,
                            participantId,
                            randomDeviceId,
                            data,
                        )
                        // Ack only after final-table data is durable. Retried sample ids are intentionally ignored.
                        connection.commit()
                        logger.info(
                            "Committed android sensor upload - studyRef = {}, participantRef = {}, " +
                                "acceptedSamples = {}, insertedSamples = {}",
                            LogSanitizer.stableFingerprint(studyId.toString(), "study"),
                            participantRef,
                            data.size,
                            inserted,
                        )
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
