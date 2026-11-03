package com.openlattice.chronicle.storage.tasks

import com.openlattice.chronicle.storage.withTransaction
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.module.kotlin.readValue
import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.tasks.HazelcastFixedRateTask
import com.geekbeast.tasks.Task
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.services.upload.UploadType
import com.openlattice.chronicle.storage.AndroidSensorDataWriter
import com.openlattice.chronicle.storage.DeletionTableLockOrder
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.UPLOAD_BUFFER
import com.openlattice.chronicle.storage.PostgresColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.RANDOM_DEVICE_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UPLOAD_DATA
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UPLOADED_AT
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UPLOAD_TYPE
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.util.LogSanitizer
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

public open class MoveAndroidSensorDataToStorageTask : HazelcastFixedRateTask<MoveToEventStorageTaskDependencies> {
    private data class MoveResult(val insertedSamples: Int, val quarantinedRows: Int)

    internal companion object {
        private const val PERIOD = 5 * 60000L
        private const val INITIAL_DELAY = 10000L
        private const val TIMEOUT_HOURS = 6L

        private val logger = LoggerFactory.getLogger(MoveAndroidSensorDataToStorageTask::class.java)
        private val mapper = ObjectMappers.newJsonMapper()

        private val executor: ListeningExecutorService =
            MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(1))

        private val QUARANTINE_MALFORMED_BUFFER_SQL = """
            INSERT INTO ${UPLOAD_BUFFER.name} (
                ${STUDY_ID.name}, ${PARTICIPANT_ID.name}, ${UPLOAD_DATA.name},
                ${UPLOADED_AT.name}, ${UPLOAD_TYPE.name}, ${RANDOM_DEVICE_ID.name}
            ) VALUES (?, ?, ?::jsonb, ?, '${UploadType.AndroidSensorRejected.name}', ?)
        """.trimIndent()
    }

    // reason: boundary catch — classify failures and surface them to the scheduler wrapper instead of reporting success
    @Suppress("TooGenericExceptionCaught")
    override fun runTask() {
        val timer = ChronicleMetrics.sensorMaterializationDurationSeconds.startTimer()
        val f = executor.submit<MoveResult> {
            RLSRequestContext.withSystemContext {
                moveToStorage()
            }
        }
        try {
            val result = f.get(TIMEOUT_HOURS, TimeUnit.HOURS)
            ChronicleMetrics.sensorMaterializationRunsTotal.labels("success").inc()
            ChronicleMetrics.sensorMaterializedSamplesTotal.inc(result.insertedSamples.toDouble())
            ChronicleMetrics.sensorMaterializationQuarantinedRowsTotal.inc(result.quarantinedRows.toDouble())
        } catch (timeoutException: TimeoutException) {
            f.cancel(true)
            ChronicleMetrics.recordBackgroundTaskFailure(ChronicleMetrics.BackgroundTask.SENSOR_MOVER)
            ChronicleMetrics.sensorMaterializationRunsTotal.labels("timeout").inc()
            throw IllegalStateException(
                "Timed out after $TIMEOUT_HOURS hour(s) when moving android sensor data",
                timeoutException,
            )
        } catch (ex: Exception) {
            ChronicleMetrics.recordBackgroundTaskFailure(ChronicleMetrics.BackgroundTask.SENSOR_MOVER)
            ChronicleMetrics.sensorMaterializationRunsTotal.labels("failure").inc()
            throw IllegalStateException("Exception when moving android sensor data", ex)
        } finally {
            timer.observeDuration()
        }
    }

    override fun getName(): String = Task.MOVE_ANDROID_SENSOR_DATA_TO_STORAGE.name

    private fun moveToStorage(): MoveResult {
        val deps = getDependency()
        return deps.storageResolver.getPlatformStorage().connection.use { platform ->
            platform.withTransaction {
                val fencedStudies = DeletionTableLockOrder.lockDrain(platform, "android_sensor_data")
                logger.info("Moving android sensor data from upload buffer to storage.")
                val result = platform.createStatement().use { stmt ->
                    platform.prepareStatement(AndroidSensorDataWriter.insertSql).use { ps ->
                        platform.prepareStatement(QUARANTINE_MALFORMED_BUFFER_SQL).use { quarantinePs ->
                            stmt.executeQuery(ChroniclePostgresTables.getMoveSql(128, UploadType.AndroidSensor, fencedStudies)).use { rs ->
                                moveBatches(ps, quarantinePs, rs)
                            }
                        }
                    }
                }

                platform.commit()
                logger.info(
                    "Successfully moved {} Android sensor samples and quarantined {} malformed legacy rows.",
                    result.insertedSamples,
                    result.quarantinedRows,
                )
                result
            }
        }
    }

    private fun moveBatches(
        ps: java.sql.PreparedStatement,
        quarantinePs: java.sql.PreparedStatement,
        rs: java.sql.ResultSet,
    ): MoveResult {
        var totalInserted = 0
        var totalQuarantined = 0
        while (rs.next()) {
            val studyId = rs.getObject(STUDY_ID.name, UUID::class.java)
            val participantId = rs.getString(PARTICIPANT_ID.name)
            val randomDeviceId = rs.getObject(RANDOM_DEVICE_ID.name, UUID::class.java)
            val rawData = rs.getString(UPLOAD_DATA.name)
            val samples = try {
                mapper.readValue<List<AndroidSensorSample>>(rawData)
            } catch (exception: JacksonException) {
                quarantineMalformedRow(
                    quarantinePs,
                    studyId,
                    participantId,
                    randomDeviceId,
                    rawData,
                    rs.getObject(UPLOADED_AT.name, OffsetDateTime::class.java),
                )
                totalQuarantined++
                logger.error(
                    "Staged malformed legacy Android sensor buffer row for quarantine - studyId = {}, " +
                        "participantRef = {}, randomDeviceId = {}",
                    studyId,
                    LogSanitizer.stableFingerprint(participantId, "participant"),
                    randomDeviceId,
                    exception,
                )
                continue
            }
            totalInserted += AndroidSensorDataWriter.write(
                ps, studyId, participantId, randomDeviceId, samples, rs.getObject(UPLOADED_AT.name, OffsetDateTime::class.java),
            )
        }
        return MoveResult(totalInserted, totalQuarantined)
    }

    private fun quarantineMalformedRow(
        ps: java.sql.PreparedStatement,
        studyId: UUID?,
        participantId: String,
        randomDeviceId: UUID?,
        rawData: String,
        uploadedAt: OffsetDateTime,
    ) {
        ps.setObject(1, studyId)
        ps.setString(2, participantId)
        ps.setString(3, rawData)
        ps.setObject(4, uploadedAt)
        ps.setObject(5, randomDeviceId)
        check(ps.executeUpdate() == 1) { "Malformed Android sensor buffer row was not quarantined" }
    }

    override fun getInitialDelay(): Long = INITIAL_DELAY
    override fun getPeriod(): Long = PERIOD
    override fun getTimeUnit(): TimeUnit = TimeUnit.MILLISECONDS

    override fun getDependenciesClass(): Class<out MoveToEventStorageTaskDependencies> =
        MoveToEventStorageTaskDependencies::class.java
}
