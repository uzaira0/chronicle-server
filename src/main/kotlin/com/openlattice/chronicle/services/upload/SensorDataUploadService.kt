package com.openlattice.chronicle.services.upload

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.JacksonException
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.util.StopWatch
import com.openlattice.chronicle.sensorkit.SensorType
import com.openlattice.chronicle.sensorkit.SensorDataSample
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.ParticipantPurgeCutoff
import com.openlattice.chronicle.storage.PostgresColumns.Companion.DEVICE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UPLOADED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UPLOAD_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UPLOAD_DATA
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.tasks.mapSensorDataToStorage
import com.openlattice.chronicle.storage.tasks.withoutDeviceName
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.DateTimeException
import java.util.*

/**
 * @author alfoncenzioka &lt;alfonce@openlattice.com&gt;
 */
public open class SensorDataUploadService(
    private val storageResolver: StorageResolver,
    // reason: DI-injected dependency wired by ChronicleServerServicesPod; kept to preserve the constructor contract
    @Suppress("UnusedPrivateProperty")
    private val studyService: StudyService
) : SensorDataUploadManager {

    internal companion object {
        private val logger = LoggerFactory.getLogger(SensorDataUploadService::class.java)
        internal val mapper: ObjectMapper = ObjectMappers.newJsonMapper()

        /**
         * 1. study id
         * 2. participant id
         * 3. upload data
         * 4. device id (UUID)
         *
         */
        private val INSERT_UPLOAD_BUFFER_SQL = """
            INSERT INTO ${ChroniclePostgresTables.UPLOAD_BUFFER.name} (${STUDY_ID.name},${PARTICIPANT_ID.name},${UPLOAD_DATA.name}, ${UPLOADED_AT.name}, ${UPLOAD_TYPE.name}, ${DEVICE_ID.name})
            VALUES (?,?,?::jsonb,now(),'${UploadType.Ios.name}',?)
        """.trimIndent()


    }

    override fun upload(
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        data: List<SensorDataSample>
    ): Int {
        require(data.size <= 10_000) { "Sensor data upload batch too large: ${data.size} samples (max 10,000)" }
        StopWatch(
            log = "Writing ${data.size} entites to Postgres upload buffer  ",
            level = Level.INFO,
            logger = logger,
        ).use {
            ParticipantPurgeCutoff.withPermittedBatch(
                storageResolver.getPlatformStorage(), studyId, participantId, data,
                { if (it.sensor == SensorType.deviceUsage) minOf(it.endDate, it.dateRecorded) else it.dateRecorded },
                capturedAt = { if (it.sensor == SensorType.deviceUsage) it.endDate else it.dateRecorded },
            ) { connection, retained ->
                // Validate only admitted samples; old payloads are silently acknowledged and discarded.
                try {
                    retained.forEach { mapSensorDataToStorage(listOf(it)) }
                } catch (invalidPayload: JacksonException) {
                    throw IllegalArgumentException("Invalid iOS sensor payload", invalidPayload)
                } catch (invalidTimestamp: DateTimeException) {
                    throw IllegalArgumentException("Invalid iOS sensor timestamp or timezone", invalidTimestamp)
                }
                if (retained.isNotEmpty()) connection.prepareStatement(INSERT_UPLOAD_BUFFER_SQL).use { ps ->
                    ps.setObject(1, studyId)
                    ps.setString(2, participantId)
                    ps.setString(3, mapper.writeValueAsString(retained.map(::withoutDeviceName)))
                    ps.setObject(4, deviceId)
                    check(ps.executeUpdate() == 1) { "iOS sensor upload was not queued" }
                }
            }
        }

//        updateParticipantStats(dataList, studyId, participantId)

        // Record upload metrics
        ChronicleMetrics.uploadTotal.labels("ios_sensor").inc()

        //Make sure device knows everything was flushed to db successfully.
        return data.size
    }


}
