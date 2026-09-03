package com.openlattice.chronicle.storage.tasks

import com.fasterxml.jackson.module.kotlin.readValue
import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.postgres.PostgresArrays
import com.geekbeast.postgres.PostgresColumnDefinition
import com.geekbeast.postgres.PostgresDatatype
import com.geekbeast.tasks.HazelcastFixedRateTask
import com.geekbeast.tasks.Task
import com.geekbeast.util.StopWatch
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.postgres.ResultSetAdapters
import com.openlattice.chronicle.sensorkit.AccelerometerBatchData
import com.openlattice.chronicle.sensorkit.CompactNumericSensorPayload
import com.openlattice.chronicle.sensorkit.KeyboardMetricsData
import com.openlattice.chronicle.sensorkit.MessagesUsageData
import com.openlattice.chronicle.sensorkit.MotionActivityEventData
import com.openlattice.chronicle.sensorkit.PedometerBatchData
import com.openlattice.chronicle.sensorkit.PhoneUsageData
import com.openlattice.chronicle.sensorkit.SensorDataSample
import com.openlattice.chronicle.sensorkit.SensorSourceDevice
import com.openlattice.chronicle.sensorkit.SensorType
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.services.upload.CompactNumericSensorPayloadValidator
import com.openlattice.chronicle.services.upload.IosScreenTimeDeviceUsageData
import com.openlattice.chronicle.services.upload.SensorDataUploadService
import com.openlattice.chronicle.services.upload.UploadType
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.PostgresEventColumns
import com.openlattice.chronicle.storage.PostgresEventTables
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.IOS_SENSOR_DATA
import com.openlattice.chronicle.storage.odtFromUsageEventColumn
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.util.SqlIdentifierValidator
import com.zaxxer.hikari.HikariDataSource
import org.apache.commons.lang3.RandomStringUtils
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.security.InvalidParameterException
import java.sql.PreparedStatement
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.min

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public open class MoveToIosEventStorageTask : HazelcastFixedRateTask<MoveToEventStorageTaskDependencies> {
    internal companion object {
        private val SENSOR_INSERT_BATCH_SIZE =
            (ChroniclePostgresTables.MAX_BIND_PARAMETERS / PostgresEventTables.IOS_SENSOR_DATA.columns.size)
        private const val PERIOD = 5 * 60000L
        private const val INITIAL_DELAY = 5000L
        private const val TIMEOUT_HOURS = 6L

        private val logger = LoggerFactory.getLogger(MoveToIosEventStorageTask::class.java)

        private val executor: ListeningExecutorService =
            MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3))
    }

    // reason: boundary catch — background task must not leak any failure type past this point
    @Suppress("TooGenericExceptionCaught")
    override fun runTask() {
        val f = executor.submit {
            RLSRequestContext.withSystemContext {
                moveToEventStorage()
            }
        }
        try {
            f.get(TIMEOUT_HOURS, TimeUnit.HOURS)
        } catch (timeoutException: TimeoutException) {
            logger.error("Timed out after ${TIMEOUT_HOURS} hour(s) when moving events to event storage.", timeoutException)
            f.cancel(true)
        } catch (ex: Exception) {
            logger.error("Exception when moving events to event storage.", ex)
        }
    }

    override fun getName(): String = Task.MOVE_IOS_DATA_TO_EVENT_STORAGE.name

    // reason: boundary catch rolls back the transaction on any failure before rethrowing; nested
    // depth is inherent to the with/try/executeQuery/forEach transaction scaffolding
    @Suppress("TooGenericExceptionCaught", "NestedBlockDepth")
    private fun moveToEventStorage() {

        with(getDependency()) {
            val platform = storageResolver.getPlatformStorage().connection
            platform.autoCommit = false
            val stmt = platform.createStatement()
            try {
                logger.info("Moving ios data from the Postgres upload buffer to event storage.")
                val queueEntriesByFlavor: MutableMap<PostgresFlavor, MutableList<SensorDataRow>> = mutableMapOf()

                stmt.executeQuery(ChroniclePostgresTables.getMoveSql(128, UploadType.Ios)).use { rs ->
                    while (rs.next()) {
                        val sensorDataSamples = ResultSetAdapters.sensorDataSamples(rs)
                        val (flavor, _) = storageResolver.resolveAndGetFlavor(sensorDataSamples.studyId)
                        queueEntriesByFlavor.getOrPut(flavor) { mutableListOf() }
                            .addAll(sensorDataSamples.toSensorDataRows())
                    }
                }

                queueEntriesByFlavor.forEach { (postgresFlavor, sensorDataEntries) ->
                    if (sensorDataEntries.isEmpty()) return@forEach
                    when (postgresFlavor) {
                        PostgresFlavor.VANILLA -> writeToEventStorage(
                            storageResolver.getEventStorageWithFlavor(PostgresFlavor.VANILLA),
                            sensorDataEntries,
                            true
                        )
                        PostgresFlavor.ANY -> writeToEventStorage(
                            storageResolver.getEventStorageWithFlavor(PostgresFlavor.VANILLA),
                            sensorDataEntries,
                            true
                        )

                        else -> throw InvalidParameterException("Invalid postgres flavor: ${postgresFlavor.name}")
                    }
                }

                platform.commit()
                platform.autoCommit = true
                stmt.close()
                platform.close()
                logger.info("Successfully moved ios data to event storage.")
                logger.info("Total number of entries for Postgres event storage: ${(queueEntriesByFlavor[PostgresFlavor.VANILLA] ?: listOf()).size}")
            } catch (ex: Exception) {
                logger.info("Unable to move data from the Postgres upload buffer to event storage.", ex)
                platform.rollback()
                stmt.close()
                platform.autoCommit = true
                platform.close()
                throw ex
            }
        }
    }

    // reason: single batched-insert transaction with prepared-statement reuse, duplicate-cleanup
    // temp table, and timestamp accumulation over closures — splitting risks the batching/rollback
    // invariants of this HIPAA storage write path
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    private fun writeToEventStorage(
        hds: HikariDataSource,
        data: List<SensorDataRow>,
        includeOnConflict: Boolean
    ): Int {
        val studies = data.map { it.studyId.toString() }.toSet()
        val participants = data.map { it.participantId }.toSet()
        //Note: May be based this off data being inserted instead?
        var minObservationTimestamp: OffsetDateTime = OffsetDateTime.MAX
        var maxObservationTimestamp: OffsetDateTime = OffsetDateTime.MIN

        return StopWatch(
            log = "writing ${data.size} entries to sensor storage.",
            level = Level.INFO,
            logger = logger
        ).use {
            val w = hds.connection.use { connection ->
                connection.autoCommit = false
                val insertBatchSize = min(data.size, SENSOR_INSERT_BATCH_SIZE)

                logger.info("Preparing primary insert statement (sensor data) with batch size $insertBatchSize")
                val insertSql = PostgresEventTables.buildMultilineInsertSensorEvents(
                    insertBatchSize,
                    includeOnConflict
                )

                val pps = connection.prepareStatement(insertSql)

                val dr = data.size % SENSOR_INSERT_BATCH_SIZE

                val fps = if (data.size > SENSOR_INSERT_BATCH_SIZE && dr != 0) {
                    logger.info("Preparing secondary insert statement with batch size $dr")
                    connection.prepareStatement(
                        PostgresEventTables.buildMultilineInsertSensorEvents(
                            dr,
                            includeOnConflict
                        )
                    )
                } else {
                    pps
                }

                val s = try {
                    data.chunked(insertBatchSize).forEach { sensorDataRows ->
                        var offset = 0
                        val ps = if (insertBatchSize == sensorDataRows.size) {
                            pps
                        } else {
                            fps
                        }

                        logger.info("Writing row of size ${sensorDataRows.size} to ios event storage.")

                        sensorDataRows.forEach {
                            val studyId = it.studyId
                            val participantId = it.participantId
                            val deviceId = it.deviceId

                            logger.trace(
                                "Writing row to storage (ios) - studyId = {}, participantRef = {}, dataSourceId = {}",
                                studyId,
                                LogSanitizer.stableFingerprint(participantId, "participant"),
                                deviceId
                            )

                            val (minOdt, maxOdt) = writeSensorDataToEventStorage(
                                ps,
                                offset,
                                studyId,
                                participantId,
                                it.sensorType,
                                it.row
                            )
                            minObservationTimestamp = minOf(minOdt, minObservationTimestamp)
                            maxObservationTimestamp = maxOf(maxOdt, maxObservationTimestamp)
                            offset += PostgresEventTables.IOS_SENSOR_DATA.columns.size
                        }
                        if (ps === pps)
                            ps.addBatch()
                    }

                    //We only call fps.executeBatch() if they ended up different objects.
                    pps.executeBatch().sum() + if (pps !== fps) {
                        fps.executeUpdate()
                    } else {
                        0
                    }
                } finally {
                    if (pps !== fps) fps.close()
                    pps.close()
                }

                /*
                 * We need to remove any duplicates that were inserted. The general approach is to use min/max recorded date
                 * and (study_id, participant_id) to count duplicates within that window and remove them. The reason for using
                 * this approach is that we don't know when duplicate may be uploaded so we need a bounded way to maintain
                 * the uniqueness invariant on each upload.
                 */

                @Suppress("DEPRECATION") val tempTableName = SqlIdentifierValidator.validateTempTableName(
                    "duplicate_ios_events_${RandomStringUtils.randomAlphanumeric(10)}"
                )

                //Create a table that contains any duplicate values introduced by this latest upload for the minimum upload_at value
                StopWatch(
                    log = "Creating duplicates table for ios studies = {} and participants = {} ",
                    level = Level.INFO,
                    logger = logger,
                    studies,
                    participants
                ).use {
                    connection.createStatement()
                        .use { stmt ->
                            stmt.execute(
                                PostgresEventTables.createTempTableOfDuplicates(
                                    tempTableName,
                                    IOS_SENSOR_DATA
                                )
                            )
                        }
                    connection.prepareStatement(PostgresEventTables.buildTempTableOfDuplicatesForIos(tempTableName))
                        .use { ps ->
                            logger.info(
                                "Earliest observation timestamp for studies = {} and participants = {} is {}",
                                studies,
                                participants,
                                minObservationTimestamp
                            )
                            logger.info(
                                "Latest observation timestamp for studies = {} and participants = {} is {}",
                                studies,
                                participants,
                                maxObservationTimestamp
                            )
                            ps.setArray(1, PostgresArrays.createTextArray(connection, studies))
                            ps.setArray(2, PostgresArrays.createTextArray(connection, participants))
                            ps.setObject(3, maxObservationTimestamp)
                            ps.setObject(4, minObservationTimestamp)
                            ps.execute()
                        }
                }

                //Delete the duplicates, if any from chronicle_usage_events and drop the temporary table.
                StopWatch(
                    log = "Deleting duplicates for ios studies = {} and participants = {} ",
                    level = Level.INFO,
                    logger = logger,
                    studies,
                    participants
                ).use {
                    connection.createStatement().use { stmt ->
                        stmt.execute(PostgresEventTables.getDeleteIosSensorDataFromTempTable(tempTableName))
                        stmt.execute("INSERT INTO ${IOS_SENSOR_DATA.name} SELECT * FROM $tempTableName")
                        stmt.execute("DROP TABLE $tempTableName")
                    }
                }

                connection.commit()
                connection.autoCommit = true
                s
            }

            //Process all the participant updates. Being lazy hear since I don't have them batched.
            data.forEach {
                updateParticipantStats(
                    it.studyId,
                    it.participantId,
                    mapOf(it.sensorType to listOf(it.row)),
                    getDependency().studyService
                )
            }
            w
        }
    }

    /**
     * @return A pair of [OffsetDateTime] where the first element in the pair is the minimum offset datetime in the
     * data and the second element is maximum element in the data.
     */
    private fun writeSensorDataToEventStorage(
        ps: PreparedStatement,
        offset: Int,
        studyId: UUID,
        participantId: String,
        sensorType: SensorType,
        dataColumns: List<SensorDataColumn>,
    ): Pair<OffsetDateTime, OffsetDateTime> {
        var minObservationTimestamp: OffsetDateTime = OffsetDateTime.MAX
        var maxObservationTimestamp: OffsetDateTime = OffsetDateTime.MIN
        ps.setString(
            offset + PostgresEventTables.getInsertSensorDataColumnIndex(PostgresEventColumns.STUDY_ID),
            studyId.toString()
        )
        ps.setString(
            offset + PostgresEventTables.getInsertSensorDataColumnIndex(PostgresEventColumns.PARTICIPANT_ID),
            participantId
        )
        ps.setString(
            offset + PostgresEventTables.getInsertSensorDataColumnIndex(PostgresEventColumns.SENSOR_TYPE),
            sensorType.name
        )

        dataColumns.forEach { dataColumn ->
            val col = dataColumn.col
            val index = offset + dataColumn.colIndex
            val value = dataColumn.value

            if (value != null && (col.name == PostgresEventColumns.START_DATE_TIME.name || col.name == PostgresEventColumns.END_DATE_TIME.name)) {
                val odt = odtFromUsageEventColumn(value)!!

                if (odt.isBefore(minObservationTimestamp)) {
                    minObservationTimestamp = odt
                }
                if (odt.isAfter(maxObservationTimestamp)) {
                    maxObservationTimestamp = odt
                }
            }

            if (value == null) {
                ps.setObject(index, null)
            } else {
                when (col.datatype) {
                    PostgresDatatype.TEXT -> ps.setString(index, value as String)
                    PostgresDatatype.DOUBLE -> ps.setDouble(index, value as Double)
                    else -> ps.setObject(index, value)
                }
            }
        }
        return minObservationTimestamp to maxObservationTimestamp
    }

    private fun getZonedDateTime(sensorDataColumns: List<SensorDataColumn>): ZonedDateTime {
        var timezone: String? = null
        var odt: OffsetDateTime? = null
        sensorDataColumns.forEach {
            if (odt == null && it.col == PostgresEventColumns.RECORDED_DATE_TIME) {
                odt = it.value as OffsetDateTime
            } else if (timezone == null && it.col == PostgresEventColumns.TIMEZONE) {
                timezone = it.value as String
            }
        }
        val recordedDateTime = checkNotNull(odt) { "Recorded date was null while processing upload." }
        val recordedTimezone = checkNotNull(timezone) { "Timezone was null while processing upload." }
        return recordedDateTime.atZoneSameInstant(ZoneId.of(recordedTimezone))
    }

    private fun updateParticipantStats(
        studyId: UUID,
        participantId: String,
        data: Map<SensorType, List<List<SensorDataColumn>>>,
        studyService: StudyManager
    ) {
        //Note: We should be able to use odt directly instead of decoding with timezone as timestamp from iphone
        //should include timezone and it is preferred in upload buffer json
        val dates = data
            .values.asSequence()
            .flatMap { sensorRowsOfType -> sensorRowsOfType.map { getZonedDateTime(it) } }
            .toSet()


        val uniqueDates: Set<LocalDate> = dates.map { it.toLocalDate() }.toSet()

        val minDate = dates.min()
        val maxDate = dates.max()

        val statsUpdate = ParticipantStats(
            studyId = studyId,
            participantId = participantId,
            iosUniqueDates = uniqueDates,
            iosLastPing = OffsetDateTime.now(),
            iosFirstDate = minDate.toOffsetDateTime(),
            iosLastDate = maxDate.toOffsetDateTime(),
        )
        studyService.insertOrUpdateParticipantStats(statsUpdate)
    }

    override fun getInitialDelay(): Long = INITIAL_DELAY

    override fun getPeriod(): Long = PERIOD

    override fun getTimeUnit(): TimeUnit = TimeUnit.MILLISECONDS

    override fun getDependenciesClass(): Class<out MoveToEventStorageTaskDependencies> =
        MoveToEventStorageTaskDependencies::class.java


}


internal fun mapSensorDataToStorage(data: List<SensorDataSample>): Map<SensorType, List<List<SensorDataColumn>>> {
    return data.groupBy { it.sensor }.mapValues { (sensorType, samples) ->
        when (sensorType) {
            SensorType.accelerometer -> mapAccelerometerData(samples)
            SensorType.pedometer -> mapPedometerData(samples)
            SensorType.motionActivity -> mapMotionActivityData(samples)
            SensorType.phoneUsage -> mapPhoneUsageData(samples)
            SensorType.deviceUsage -> mapDeviceUsageData(samples)
            SensorType.keyboardMetrics -> mapKeyboardMetricsData(samples)
            SensorType.messagesUsage -> mapMessagesUsageData(samples)
        }
    }
}

private fun mapPedometerData(data: List<SensorDataSample>): List<List<SensorDataColumn>> {
    return mapRawPayloadData(data) { rawPayload ->
        val payload = SensorDataUploadService.mapper.readValue<PedometerBatchData>(rawPayload)
        require(payload.schemaVersion == 1) { "Unsupported pedometer payload schema version" }
        require(payload.provenance == "os_buffered") { "Unsupported pedometer payload provenance" }
        require(payload.numberOfSteps >= 0) { "Pedometer step count cannot be negative" }
        payload.floorsAscended?.let {
            require(it >= 0) { "Pedometer floors ascended cannot be negative" }
        }
        payload.floorsDescended?.let {
            require(it >= 0) { "Pedometer floors descended cannot be negative" }
        }
        listOf(
            payload.distanceMeters,
            payload.averageActivePaceSecondsPerMeter,
            payload.currentPaceSecondsPerMeter,
            payload.currentCadenceStepsPerSecond
        ).filterNotNull().forEach { value ->
            require(value.isFinite() && value >= 0) { "Pedometer numeric metrics must be finite and nonnegative" }
        }
    }
}

private fun mapMotionActivityData(data: List<SensorDataSample>): List<List<SensorDataColumn>> {
    return mapRawPayloadData(data) { rawPayload ->
        val payload = SensorDataUploadService.mapper.readValue<MotionActivityEventData>(rawPayload)
        require(payload.schemaVersion == 1) { "Unsupported motion activity payload schema version" }
        require(payload.provenance == "os_buffered") { "Unsupported motion activity payload provenance" }
        require(payload.confidence in setOf("low", "medium", "high", "unknown")) {
            "Unsupported motion activity confidence"
        }
        require(
            payload.stationary || payload.walking || payload.running || payload.automotive ||
                payload.cycling || payload.unknown
        ) { "Motion activity payload must identify at least one activity state" }
    }
}

private fun mapRawPayloadData(
    data: List<SensorDataSample>,
    validate: (String) -> Unit,
): List<List<SensorDataColumn>> {
    val nullCols = nullifyCols(
        (PostgresEventColumns.DEVICE_USAGE_SENSOR_COLS - PostgresEventColumns.RAW_SENSOR_PAYLOAD) +
            PostgresEventColumns.PHONE_USAGE_SENSOR_COLS +
            PostgresEventColumns.MESSAGES_USAGE_SENSOR_COLS +
            PostgresEventColumns.KEYBOARD_METRICS_SENSOR_COLS
    )
    return data.map { sample ->
        validate(sample.data)
        buildList {
            add(SensorDataColumn(PostgresEventColumns.RAW_SENSOR_PAYLOAD, sample.data))
            addAll(mapSharedColumns(sample))
            addAll(nullCols)
        }
    }
}

private fun mapAccelerometerData(data: List<SensorDataSample>): List<List<SensorDataColumn>> {
    val nullCols = nullifyCols(
        (PostgresEventColumns.DEVICE_USAGE_SENSOR_COLS - PostgresEventColumns.RAW_SENSOR_PAYLOAD) +
            PostgresEventColumns.PHONE_USAGE_SENSOR_COLS +
            PostgresEventColumns.MESSAGES_USAGE_SENSOR_COLS +
            PostgresEventColumns.KEYBOARD_METRICS_SENSOR_COLS
    )
    return data.map { sample ->
        validateAccelerometerPayload(sample.data)
        buildList {
            add(SensorDataColumn(PostgresEventColumns.RAW_SENSOR_PAYLOAD, sample.data))
            addAll(mapSharedColumns(sample))
            addAll(nullCols)
        }
    }
}

private fun validateAccelerometerPayload(data: String) {
    when (SensorDataUploadService.mapper.readTree(data).path("schemaVersion").asInt(-1)) {
        1 -> SensorDataUploadService.mapper.readValue<AccelerometerBatchData>(data)
        CompactNumericSensorPayloadValidator.QUANTIZED_SCHEMA_VERSION,
        CompactNumericSensorPayloadValidator.SCHEMA_VERSION -> {
            val payload = SensorDataUploadService.mapper.readValue<CompactNumericSensorPayload>(data)
            CompactNumericSensorPayloadValidator.validate(payload)
            require(payload.channels.map { it.name } == listOf("x", "y", "z")) {
                "Accelerometer compact payload must define x, y, and z channels in order"
            }
            require(payload.channels.all { it.unit == "g" }) {
                "Accelerometer compact payload channels must use g units"
            }
        }
        else -> throw IllegalArgumentException("Unsupported accelerometer payload schema version")
    }
}

private fun mapPhoneUsageData(data: List<SensorDataSample>): List<List<SensorDataColumn>> {
    val result: MutableList<List<SensorDataColumn>> = mutableListOf()
    val nulCols =
        nullifyCols(
            PostgresEventColumns.DEVICE_USAGE_SENSOR_COLS +
                PostgresEventColumns.MESSAGES_USAGE_SENSOR_COLS +
                PostgresEventColumns.KEYBOARD_METRICS_SENSOR_COLS -
                PostgresEventColumns.TOTAL_UNIQUE_CONTACTS
        )

    data.forEach {
        val phoneUsageData: PhoneUsageData = SensorDataUploadService.mapper.readValue(it.data)
        val cols = mutableListOf(
            SensorDataColumn(PostgresEventColumns.TOTAL_INCOMING_CALLS, phoneUsageData.totalIncomingCalls),
            SensorDataColumn(PostgresEventColumns.TOTAL_OUTGOING_CALLS, phoneUsageData.totalOutgoingCalls),
            SensorDataColumn(PostgresEventColumns.TOTAL_CALL_DURATION, phoneUsageData.totalPhoneDuration),
            SensorDataColumn(PostgresEventColumns.TOTAL_UNIQUE_CONTACTS, phoneUsageData.totalUniqueContacts)
        )
        cols.addAll(mapSharedColumns(it))
        cols.addAll(nulCols)
        result.add(cols)
    }

    return result
}

// reason: nested forEach with labeled early-returns assembling exact column-row permutations for
// the storage wire format; flattening would change the emitted row set
@Suppress("LongMethod", "NestedBlockDepth")
private fun mapDeviceUsageData(data: List<SensorDataSample>): List<List<SensorDataColumn>> {
    val result: MutableList<List<SensorDataColumn>> = mutableListOf()
    val defaultNullCols =
        nullifyCols(
            PostgresEventColumns.PHONE_USAGE_SENSOR_COLS +
                PostgresEventColumns.MESSAGES_USAGE_SENSOR_COLS +
                PostgresEventColumns.KEYBOARD_METRICS_SENSOR_COLS
        )

    data.forEach sample@{ sample ->
        val deviceUsageData: IosScreenTimeDeviceUsageData = SensorDataUploadService.mapper.readValue(sample.data)
        val appCategories: Set<String> = deviceUsageData.appUsage.keys + deviceUsageData.webUsage.keys
        val summaryCols = listOf(
            SensorDataColumn(PostgresEventColumns.TOTAL_UNLOCK_DURATION, deviceUsageData.totalUnlockDuration),
            SensorDataColumn(PostgresEventColumns.TOTAL_SCREEN_WAKES, deviceUsageData.totalScreenWakes),
            SensorDataColumn(PostgresEventColumns.TOTAL_UNLOCKS, deviceUsageData.totalUnlocks)
        )
        val metadataCols = iosScreenTimeMetadataCols(deviceUsageData)

        if (appCategories.isEmpty()) {
            val cols = nullifyCols(
                setOf(
                    PostgresEventColumns.APP_CATEGORY,
                    PostgresEventColumns.APP_USAGE_TIME,
                    PostgresEventColumns.TEXT_INPUT_DURATION,
                    PostgresEventColumns.TEXT_INPUT_SOURCE,
                    PostgresEventColumns.BUNDLE_IDENTIFIER,
                    PostgresEventColumns.APP_CATEGORY_WEB_DURATION
                )
            ).toMutableList()
            cols.addAll(summaryCols)
            cols.addAll(metadataCols)
            cols.addAll(defaultNullCols)
            cols.addAll(mapSharedColumns(sample))
            result.add(cols)
            return@sample
        }

        appCategories.forEach categories@{ category ->
            val appUsages = deviceUsageData.appUsage.getOrDefault(category, listOf())
            val webUsage = deviceUsageData.webUsage[category]

            if (appUsages.isEmpty()) {
                val cols = nullifyCols(
                    setOf(
                        PostgresEventColumns.TEXT_INPUT_SOURCE,
                        PostgresEventColumns.TEXT_INPUT_DURATION,
                        PostgresEventColumns.APP_USAGE_TIME,
                        PostgresEventColumns.APP_CATEGORY,
                        PostgresEventColumns.BUNDLE_IDENTIFIER
                    )
                ).toMutableList()
                cols.add(SensorDataColumn(PostgresEventColumns.APP_CATEGORY_WEB_DURATION, webUsage))
                cols.addAll(summaryCols)
                cols.addAll(metadataCols)
                cols.addAll(defaultNullCols)
                cols.addAll(mapSharedColumns(sample))
                result.add(cols)

                return@categories
            }

            appUsages.forEach usage@{ usage ->
                if (usage.textInputSessions.isEmpty()) {
                    val cols = mutableListOf(
                        SensorDataColumn(PostgresEventColumns.TEXT_INPUT_SOURCE, null),
                        SensorDataColumn(PostgresEventColumns.TEXT_INPUT_DURATION, null),
                        SensorDataColumn(PostgresEventColumns.APP_USAGE_TIME, usage.usageTime),
                        SensorDataColumn(PostgresEventColumns.APP_CATEGORY, category),
                        SensorDataColumn(PostgresEventColumns.BUNDLE_IDENTIFIER, usage.bundleIdentifier),
                        SensorDataColumn(PostgresEventColumns.APP_CATEGORY_WEB_DURATION, webUsage),
                    )
                    cols.addAll(summaryCols)
                    cols.addAll(metadataCols)
                    cols.addAll(defaultNullCols)
                    cols.addAll(mapSharedColumns(sample))
                    result.add(cols)

                    return@usage
                }

                usage.textInputSessions.forEach { (inputSource, duration) ->
                    val cols = mutableListOf(
                        SensorDataColumn(PostgresEventColumns.TEXT_INPUT_SOURCE, inputSource),
                        SensorDataColumn(PostgresEventColumns.TEXT_INPUT_DURATION, duration),
                        SensorDataColumn(PostgresEventColumns.APP_USAGE_TIME, usage.usageTime),
                        SensorDataColumn(PostgresEventColumns.APP_CATEGORY, category),
                        SensorDataColumn(PostgresEventColumns.BUNDLE_IDENTIFIER, usage.bundleIdentifier),
                        SensorDataColumn(PostgresEventColumns.APP_CATEGORY_WEB_DURATION, webUsage)
                    )
                    cols.addAll(metadataCols)
                    cols.addAll(defaultNullCols)
                    cols.addAll(summaryCols)
                    cols.addAll(mapSharedColumns(sample))
                    result.add(cols)
                }
            }
        }
    }
    return result
}

private fun iosScreenTimeMetadataCols(deviceUsageData: IosScreenTimeDeviceUsageData): List<SensorDataColumn> {
    return listOf(
        SensorDataColumn(PostgresEventColumns.IOS_SCREEN_TIME_SOURCE, deviceUsageData.screenTimeSource),
        SensorDataColumn(PostgresEventColumns.IOS_SCREEN_TIME_CONFIDENCE, deviceUsageData.screenTimeConfidence),
        SensorDataColumn(PostgresEventColumns.IOS_SCREEN_TIME_ROW_KIND, deviceUsageData.screenTimeRowKind),
        SensorDataColumn(PostgresEventColumns.IOS_SCREEN_TIME_APP_LABEL, deviceUsageData.screenTimeAppLabel),
        SensorDataColumn(PostgresEventColumns.IOS_SCREEN_TIME_BUNDLE_ID, deviceUsageData.screenTimeBundleIdentifier),
        SensorDataColumn(PostgresEventColumns.IOS_SCREEN_TIME_WEB_DOMAIN, deviceUsageData.screenTimeWebDomain),
        SensorDataColumn(PostgresEventColumns.IOS_SCREEN_TIME_RAW_SOURCE_LABEL, deviceUsageData.screenTimeRawSourceLabel),
        SensorDataColumn(
            PostgresEventColumns.IOS_SCREEN_TIME_NOTIFICATION_COUNT,
            deviceUsageData.screenTimeNotificationCount,
        ),
        SensorDataColumn(PostgresEventColumns.IOS_SCREEN_TIME_PICKUP_COUNT, deviceUsageData.screenTimePickupCount),
    )
}

private fun mapKeyboardMetricsData(data: List<SensorDataSample>): List<List<SensorDataColumn>> {
    val result: MutableList<MutableList<SensorDataColumn>> = mutableListOf()
    val nullCols =
        nullifyCols(
            PostgresEventColumns.PHONE_USAGE_SENSOR_COLS +
                PostgresEventColumns.MESSAGES_USAGE_SENSOR_COLS +
                PostgresEventColumns.DEVICE_USAGE_SENSOR_COLS
        )

    data.forEach { sample ->
        val keyboardMetricsData: KeyboardMetricsData = SensorDataUploadService.mapper.readValue(sample.data)
        val sentiments =
            keyboardMetricsData.emojiCountBySentiment.keys + keyboardMetricsData.wordCountBySentiment.keys

        if (sentiments.isEmpty()) {
            val cols = mapDefaultKeyboardMetricsCols(keyboardMetricsData).toMutableList()
            cols.add(SensorDataColumn(PostgresEventColumns.SENTIMENT, null))
            cols.add(SensorDataColumn(PostgresEventColumns.SENTIMENT_WORD_COUNT, null))
            cols.add(SensorDataColumn(PostgresEventColumns.SENTIMENT_EMOJI_COUNT, null))
            cols.addAll(mapSharedColumns(sample))
            cols.addAll(nullCols)
            result.add(cols)
            return@forEach
        }
        sentiments.forEach { sentiment ->
            val cols = mapDefaultKeyboardMetricsCols(keyboardMetricsData).toMutableList()
            cols.add(SensorDataColumn(PostgresEventColumns.SENTIMENT, sentiment))
            cols.add(
                SensorDataColumn(
                    PostgresEventColumns.SENTIMENT_WORD_COUNT,
                    keyboardMetricsData.wordCountBySentiment[sentiment]
                )
            )
            cols.add(
                SensorDataColumn(
                    PostgresEventColumns.SENTIMENT_EMOJI_COUNT,
                    keyboardMetricsData.emojiCountBySentiment[sentiment]
                )
            )
            cols.addAll(mapSharedColumns(sample))
            cols.addAll(nullCols)
            result.add(cols)
        }
    }

    return result
}

private fun mapDefaultKeyboardMetricsCols(data: KeyboardMetricsData): List<SensorDataColumn> {
    return listOf(
        SensorDataColumn(PostgresEventColumns.TOTAL_WORDS, data.totalWords),
        SensorDataColumn(PostgresEventColumns.TOTAL_ALTERED_WORDS, data.totalAlteredWords),
        SensorDataColumn(PostgresEventColumns.TOTAL_TAPS, data.totalTaps),
        SensorDataColumn(PostgresEventColumns.TOTAL_DRAGS, data.totalDrags),
        SensorDataColumn(PostgresEventColumns.TOTAL_DELETES, data.totalDeletes),
        SensorDataColumn(PostgresEventColumns.TOTAL_EMOJIS, data.totalEmojis),
        SensorDataColumn(PostgresEventColumns.TOTAL_PATHS, data.totalPaths),
        SensorDataColumn(PostgresEventColumns.TOTAL_PATH_LENGTH, data.totalPathLength),
        SensorDataColumn(PostgresEventColumns.TOTAL_PATH_TIME, data.totalPathTime),
        SensorDataColumn(PostgresEventColumns.TOTAL_AUTO_CORRECTIONS, data.totalAutoCorrections),
        SensorDataColumn(PostgresEventColumns.TOTAL_SPACE_CORRECTIONS, data.totalSpaceCorrections),
        SensorDataColumn(PostgresEventColumns.TOTAL_TRANSPOSITION_CORRECTIONS, data.totalTranspositionCorrections),
        SensorDataColumn(PostgresEventColumns.TOTAL_INSERT_KEY_CORRECTIONS, data.totalInsertKeyCorrections),
        SensorDataColumn(PostgresEventColumns.TOTAL_RETRO_CORRECTIONS, data.totalRetroCorrections),
        SensorDataColumn(PostgresEventColumns.TOTAL_SKIP_TOUCH_CORRECTIONS, data.totalSkipTouchCorrections),
        SensorDataColumn(PostgresEventColumns.TOTAL_NEAR_KEY_CORRECTIONS, data.totalNearKeyCorrections),
        SensorDataColumn(PostgresEventColumns.TOTAL_SUBSTITUTION_CORRECTIONS, data.totalSubstitutionCorrections),
        SensorDataColumn(PostgresEventColumns.TOTAL_TEST_HIT_CORRECTIONS, data.totalHitTestCorrections),
        SensorDataColumn(PostgresEventColumns.TOTAL_TYPING_DURATION, data.totalTypingDuration),
        SensorDataColumn(PostgresEventColumns.TOTAL_PATH_PAUSES, data.totalPathPauses),
        SensorDataColumn(PostgresEventColumns.TOTAL_PAUSES, data.totalPauses),
        SensorDataColumn(PostgresEventColumns.TOTAL_TYPING_EPISODES, data.totalTypingEpisodes),
        SensorDataColumn(PostgresEventColumns.TYPING_SPEED, data.typingSpeed),
        SensorDataColumn(PostgresEventColumns.PATH_TYPING_SPEED, data.pathTypingSpeed)
    )
}

private fun mapMessagesUsageData(data: List<SensorDataSample>): List<List<SensorDataColumn>> {
    val result: MutableList<List<SensorDataColumn>> = mutableListOf()
    val nulCols =
        nullifyCols(
            PostgresEventColumns.DEVICE_USAGE_SENSOR_COLS +
                PostgresEventColumns.PHONE_USAGE_SENSOR_COLS +
                PostgresEventColumns.KEYBOARD_METRICS_SENSOR_COLS -
                PostgresEventColumns.TOTAL_UNIQUE_CONTACTS
        )

    data.forEach {
        val messagesUsageData: MessagesUsageData = SensorDataUploadService.mapper.readValue(it.data)
        val cols = mutableListOf(
            SensorDataColumn(PostgresEventColumns.TOTAL_INCOMING_MESSAGES, messagesUsageData.totalIncomingMessages),
            SensorDataColumn(PostgresEventColumns.TOTAL_OUTGOING_MESSAGES, messagesUsageData.totalOutgoingMessages),
            SensorDataColumn(PostgresEventColumns.TOTAL_UNIQUE_CONTACTS, messagesUsageData.totalUniqueContacts)
        )
        cols.addAll(mapSharedColumns(it))
        cols.addAll(nulCols)
        result.add(cols)
    }

    return result
}

private fun mapSharedColumns(dataSample: SensorDataSample): List<SensorDataColumn> {
    val device: SensorSourceDevice = SensorDataUploadService.mapper.readValue(dataSample.device)

    return listOf(
        SensorDataColumn(PostgresEventColumns.SAMPLE_ID, dataSample.id.toString()),
        SensorDataColumn(PostgresEventColumns.SENSOR_TYPE, dataSample.sensor.name),
        SensorDataColumn(PostgresEventColumns.SAMPLE_DURATION, dataSample.duration),
        SensorDataColumn(
            PostgresEventColumns.RECORDED_DATE_TIME,
            dataSample.dateRecorded.plusSeconds(30).truncatedTo(ChronoUnit.MINUTES)
        ),
        SensorDataColumn(PostgresEventColumns.START_DATE_TIME, dataSample.startDate),
        SensorDataColumn(PostgresEventColumns.END_DATE_TIME, dataSample.endDate),
        SensorDataColumn(PostgresEventColumns.TIMEZONE, dataSample.timezone),
        SensorDataColumn(PostgresEventColumns.DEVICE_VERSION, device.systemVersion),
        SensorDataColumn(PostgresEventColumns.DEVICE_NAME, device.name),
        SensorDataColumn(PostgresEventColumns.DEVICE_MODEL, device.model),
        SensorDataColumn(PostgresEventColumns.DEVICE_SYSTEM_NAME, device.name),
        SensorDataColumn(PostgresEventColumns.EXACT_RECORDED_DATE_TIME, dataSample.dateRecorded)
    )
}

private fun nullifyCols(cols: Set<PostgresColumnDefinition>): List<SensorDataColumn> {
    return cols.map { SensorDataColumn(it, null) }
}

internal data class SensorDataColumn(
    val col: PostgresColumnDefinition,
    val value: Any?
) {
    val colIndex: Int = PostgresEventTables.getInsertSensorDataColumnIndex(col)
}

internal data class SensorDataRow(
    val studyId: UUID,
    val participantId: String,
    val sensorType: SensorType,
    val row: List<SensorDataColumn>,
    val uploadedAt: OffsetDateTime,
    val deviceId: UUID,
)

public data class SensorDataEntries(
    val studyId: UUID,
    val participantId: String,
    val data: List<SensorDataSample>,
    val uploadedAt: OffsetDateTime,
    val deviceId: UUID,
) {
    internal fun toSensorDataRows(): List<SensorDataRow> {
        return mapSensorDataToStorage(data).flatMap { (sensorType, rows) ->
            rows.map { row ->
                SensorDataRow(
                    studyId,
                    participantId,
                    sensorType,
                    row,
                    uploadedAt,
                    deviceId
                )
            }
        }
    }
}
