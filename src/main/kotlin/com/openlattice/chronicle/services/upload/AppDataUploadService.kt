package com.openlattice.chronicle.services.upload

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.postgres.PostgresArrays
import com.geekbeast.postgres.PostgresColumnDefinition
import com.geekbeast.postgres.PostgresDatatype
import com.geekbeast.util.StopWatch
import com.google.common.collect.SetMultimap
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.android.fromInteractionType
import com.openlattice.chronicle.constants.EdmConstants.DATE_LOGGED_FQN
import com.openlattice.chronicle.constants.EdmConstants.FULL_NAME_FQN
import com.openlattice.chronicle.constants.EdmConstants.RECORD_TYPE_FQN
import com.openlattice.chronicle.constants.EdmConstants.TIMEZONE_FQN
import com.openlattice.chronicle.constants.EdmConstants.TITLE_FQN
import com.openlattice.chronicle.constants.EdmConstants.USER_FQN
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.postgres.ResultSetAdapters
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.legacy.LegacyEdmResolver
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.MAX_BIND_PARAMETERS
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.UPLOAD_BUFFER
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.getMoveSql
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.getScopedMoveSql
import com.openlattice.chronicle.storage.PostgresColumns
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UPLOAD_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UPLOAD_DATA
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APPLICATION_LABEL
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APP_PACKAGE_NAME
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.ACTIVITY_CLASS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.COLLECTED_AT
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.EVENT_TYPE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.FQNS_TO_COLUMNS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.INTERACTION_TYPE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.PARTICIPANT_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.STUDY_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.TIMESTAMP
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.TIMEZONE
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.UPLOADED_AT
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.USERNAME
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.CHRONICLE_USAGE_EVENTS
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.buildMultilineInsertUsageEvents
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.buildTempTableOfDuplicates
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.createTempTableOfDuplicates
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.getDeleteUsageEventsFromTempTable
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.getInsertUsageEventColumnIndex
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.odtFromUsageEventColumn
import com.openlattice.chronicle.storage.zdtFromAndroidColumns
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.util.SqlIdentifierValidator
import com.zaxxer.hikari.HikariDataSource
import org.apache.commons.lang3.RandomStringUtils
import com.openlattice.chronicle.observability.ChronicleMetrics
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.security.InvalidParameterException
import java.time.OffsetDateTime
import java.util.*
import java.util.concurrent.Semaphore
import kotlin.math.min

/**
 * @author alfoncenzioka &lt;alfonce@openlattice.com&gt;
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */

public open class AppDataUploadService(
    private val storageResolver: StorageResolver,
    private val enrollmentManager: EnrollmentManager,
    private val studyManager: StudyManager,
) : AppDataUploadManager {
    internal companion object {
        private val logger = LoggerFactory.getLogger(AppDataUploadService::class.java)
        private val UPLOAD_AT_INDEX = getInsertUsageEventColumnIndex(UPLOADED_AT)
        private val COLLECTED_AT_INDEX = getInsertUsageEventColumnIndex(COLLECTED_AT)
        private val mapper = ObjectMappers.getJsonMapper()
        private val semaphore = Semaphore(10)
        // Derived so column growth can never push a full batch past the bind-parameter ceiling.
        private val EVENT_INSERT_BATCH_SIZE =
            (MAX_BIND_PARAMETERS - 1) / CHRONICLE_USAGE_EVENTS.columns.size
        private const val MOVE_BATCH_SIZE = 128


        /**
         * 1. study id
         * 2. participant id
         * 3. upload data
         * 4. uploaded at
         * 5. device id
         */
        private val INSERT_USAGE_EVENTS_SQL = """
                    INSERT INTO ${UPLOAD_BUFFER.name} (${STUDY_ID.name},${PARTICIPANT_ID.name},${UPLOAD_DATA.name}, ${PostgresColumns.UPLOADED_AT.name}, ${UPLOAD_TYPE.name}, ${PostgresColumns.DEVICE_ID.name})
                    VALUES (?,?,?::jsonb,?,'${UploadType.Android.name}',?)
                """.trimIndent()

    }

    init {
//        executor.execute {
//            while (true) {
//                moveToEventStorage()
//                Thread.sleep(5 * 60 * 1000)
//            }
//        }
    }

    /**
     * This routine implements once and only once append of client data.
     *
     * Assumptions:
     * - Client generates a UUID uniformly at random for each event and stores it in the id field.
     * - Client will retry upload until receives successful acknowledgement from the server.
     *
     * Data is first written into a postgres table which is periodically flushed to Postgres event storage for long term storage.
     *
     * The probability of the same UUID being generated twice for the same organization id/participant id/device
     * id/timestamp is unlikely to happen in the lifetime of our universe.
     */
    // reason: boundary catch — logs upload metrics for any failure type then rethrows; must not leak any failure type past this point
    @Suppress("TooGenericExceptionCaught")
    override fun uploadAndroidUsageEvents(
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        data: List<ChronicleUsageEvent>,
        uploadedAt: OffsetDateTime,
    ): Int {
        require(data.size <= 10_000) { "Upload batch too large: ${data.size} events (max 10,000)" }
        val participantRef = LogSanitizer.stableFingerprint(participantId, "participant")
        StopWatch(
            log = "logging ${data.size} entries for studyId = {}, participantRef = {}, dataSourceId = {}",
            level = Level.INFO,
            logger = logger,
            studyId,
            participantRef,
            deviceId
        ).use {
            try {
                val (flavor, hds) = storageResolver.resolveAndGetFlavor(studyId)

                // The sole production caller is StudyController, whose mobile-upload gate owns
                // participation and datasource authorization and maps every rejection to HTTP 403.
                // Rechecking here added two hot-path queries and could race into a divergent 500.

                logger.info(
                    "attempting to log data - studyId = {}, participantRef = {}, dataSourceId = {}",
                    studyId,
                    participantRef,
                    deviceId
                )

                val mappedData = filter(mapToStorageModel(data))
                val expectedSize = data.size
                doWrite(studyId, participantId, deviceId, mappedData, expectedSize, uploadedAt)

                // Record upload metrics
                ChronicleMetrics.uploadTotal.labels("android_usage").inc()

                return data.size
            } catch (exception: Exception) {
                logger.error(
                    "error logging data - studyId = {}, participantRef = {}, dataSourceId = {}",
                    studyId,
                    participantRef,
                    deviceId,
                    exception
                )
                ChronicleMetrics.uploadErrors.labels("android_usage", exception.javaClass.simpleName).inc()
                throw exception
            }
        }
    }

    /**
     * This routine implements once and only once append of client data.
     *
     * Assumptions:
     * - Client generates a UUID uniformly at random for each event and stores it in the id field.
     * - Client will retry upload until receives successful acknowledgement from the server.
     *
     * Data is first written into a postgres table which is periodically flushed to Postgres event storage for long term storage.
     *
     * The probability of the same UUID being generated twice for the same organization id/participant id/device
     * id/timestamp is unlikely to happen in the lifetime of our universe.
     */
    // reason: boundary catch — logs and rethrows any failure type from the upload path; must not leak any failure type past this point
    @Suppress("TooGenericExceptionCaught")
    override fun upload(
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        data: List<SetMultimap<UUID, Any>>,
        uploadedAt: OffsetDateTime,
    ): Int {
        require(data.size <= 10_000) { "Upload batch too large: ${data.size} entries (max 10,000)" }
        val participantRef = LogSanitizer.stableFingerprint(participantId, "participant")
        StopWatch(
            log = "logging ${data.size} entries for studyId = {}, participantRef = {}, dataSourceId = {}",
            level = Level.INFO,
            logger = logger,
            studyId,
            participantRef,
            deviceId
        ).use {
            try {
                val (flavor, hds) = storageResolver.resolveAndGetFlavor(studyId)

                val status = enrollmentManager.getParticipationStatus(studyId, participantId)
                if (ParticipationStatus.NOT_ENROLLED == status) {
                    logger.warn(
                        "participant is not enrolled, ignoring upload - studyId = {}, participantRef = {}, dataSourceId = {}",
                        studyId,
                        participantRef,
                        deviceId
                    )
                    return 0
                }
                val deviceEnrolled = enrollmentManager.isKnownDatasource(studyId, participantId, deviceId)

                if (!deviceEnrolled) {
                    logger.error(
                        "data source not found, ignoring upload - studyId = {}, participantRef = {}, dataSourceId = {}",
                        studyId,
                        participantRef,
                        deviceId
                    )
                    return 0
                }

                logger.info(
                    "attempting to log data - studyId = {}, participantRef = {}, dataSourceId = {}",
                    studyId,
                    participantRef,
                    deviceId
                )

                val mappedData = filter(mapLegacyDataToStorageModel(data))
                val expectedSize = data.size

                doWrite(studyId, participantId, deviceId, mappedData, expectedSize, uploadedAt)

                return expectedSize
            } catch (exception: Exception) {
                logger.error(
                    "error logging data - studyId = {}, participantRef = {}, dataSourceId = {}",
                    studyId,
                    participantRef,
                    deviceId,
                    exception
                )
                throw exception
            }
        }
    }

    private fun doWrite(
        studyId: UUID,
        participantId: String,
        deviceId: UUID,
        mappedData: Sequence<Map<String, UsageEventColumn>>,
        expectedSize: Int,
        uploadedAt: OffsetDateTime,
    ): Int {
        val dataList = mappedData.toList()
        val written = StopWatch(
            log = "Writing ${dataList.size} entites (expected: $expectedSize) to Postgres upload buffer " +
                "for studyId = $studyId, participantId = $participantId ",
            level = Level.INFO,
            logger = logger,
        ).use {
            storageResolver.getPlatformStorage().connection.use { connection ->
                connection.prepareStatement(INSERT_USAGE_EVENTS_SQL).use { ps ->
                    ps.setObject(1, studyId)
                    ps.setString(2, participantId)
                    ps.setString(3, mapper.writeValueAsString(dataList))
                    ps.setObject(4, uploadedAt)
                    ps.setObject(5, deviceId)
                    ps.executeUpdate()
                }
            }
        }

        updateParticipantStats(dataList, studyId, participantId)

        // We may write fewer entities than provided; we return the number processed so the client knows all is good.
        if (expectedSize != dataList.size) {
            //Should probably be an assertion as this should never happen.
            logger.warn("Wrote ${dataList.size} entities, but expected to write $expectedSize entities")
        }


        //Currently nothing is done with written, but here in case we need it in the future.
        return written
    }


    // reason: boundary catch — transactional buffer-to-storage move must log and rethrow any
    // failure type; the nested result-set iteration over the transaction can't be extracted safely
    @Suppress("TooGenericExceptionCaught", "NestedBlockDepth")
    override fun moveToEventStorage() {
        try {
            semaphore.tryRunWithPermit {
                moveToEventStorageBatch(null)
            }
        } catch (ex: Exception) {
            logger.error("Unable to move data from the Postgres upload buffer to event storage.", ex)
            throw ex
        }
    }

    /**
     * Drains every currently buffered Android usage row for one exact subject.
     *
     * The scoped claim waits for row locks instead of skipping them. Consequently, a concurrent
     * background/global claimant must commit (including its destination write) before this method
     * can observe an empty subject queue and return.
     */
    @Suppress("TooGenericExceptionCaught")
    public fun moveToEventStorage(studyId: UUID, participantId: String) {
        require(participantId.isNotBlank()) { "participantId must not be blank" }
        val scope = UploadBufferScope(studyId, participantId)
        semaphore.acquire()
        try {
            do {
                val claimedRows = moveToEventStorageBatch(scope)
            } while (claimedRows > 0)
            checkSubjectMutationAllowed(scope)
        } catch (ex: Exception) {
            logger.error(
                "Unable to move scoped data from the Postgres upload buffer to event storage for study {}.",
                studyId,
                ex,
            )
            throw ex
        } finally {
            semaphore.release()
        }
    }

    @Suppress("TooGenericExceptionCaught", "NestedBlockDepth")
    private fun moveToEventStorageBatch(scope: UploadBufferScope?): Int {
        logger.info(
            "Moving data from the Postgres upload buffer to event storage{}.",
            if (scope == null) "" else " for study ${scope.studyId}",
        )
        val queueEntriesByFlavor: MutableMap<PostgresFlavor, MutableList<UsageEventQueueEntry>> = mutableMapOf()
        var claimedRows = 0
        storageResolver.getPlatformStorage().connection.use { platform ->
            val previousAutoCommit = platform.autoCommit
            platform.autoCommit = false
            try {
                val moveSql = if (scope == null) {
                    getMoveSql(MOVE_BATCH_SIZE, UploadType.Android)
                } else {
                    getScopedMoveSql(MOVE_BATCH_SIZE, UploadType.Android)
                }
                platform.prepareStatement(moveSql).use { statement ->
                    if (scope != null) {
                        statement.setObject(1, scope.studyId)
                        statement.setString(2, scope.participantId)
                    }
                    statement.executeQuery().use { resultSet ->
                        while (resultSet.next()) {
                            claimedRows += 1
                            val usageEventQueueEntries = ResultSetAdapters.usageEventQueueEntries(resultSet)
                            val (flavor, _) = storageResolver.resolveAndGetFlavor(usageEventQueueEntries.studyId)
                            queueEntriesByFlavor.getOrPut(flavor) { mutableListOf() }
                                .addAll(usageEventQueueEntries.toEntryList())
                        }
                    }
                }
                logger.info(
                    "Total number of entries for Postgres event storage: " +
                        "${(queueEntriesByFlavor[PostgresFlavor.VANILLA] ?: listOf()).size}"
                )
                queueEntriesByFlavor.forEach { (postgresFlavor, usageEventQueueEntries) ->
                    if (usageEventQueueEntries.isEmpty()) return@forEach
                    when (postgresFlavor) {
                        PostgresFlavor.VANILLA -> writeToEventStorage(
                            storageResolver.getEventStorageWithFlavor(PostgresFlavor.VANILLA),
                            usageEventQueueEntries
                        )
                        PostgresFlavor.ANY -> writeToEventStorage(
                            storageResolver.getEventStorageWithFlavor(PostgresFlavor.VANILLA),
                            usageEventQueueEntries
                        )
                        else -> throw InvalidParameterException("Invalid postgres flavor: ${postgresFlavor.name}")
                    }
                }
                platform.commit()
            } catch (ex: Exception) {
                try {
                    platform.rollback()
                } catch (rollbackFailure: Exception) {
                    ex.addSuppressed(rollbackFailure)
                }
                throw ex
            } finally {
                try {
                    platform.autoCommit = previousAutoCommit
                } catch (restoreFailure: Exception) {
                    logger.warn("Unable to restore upload-buffer connection auto-commit", restoreFailure)
                }
            }
        }
        logger.info("Successfully moved {} upload-buffer row(s) to event storage.", claimedRows)
        return claimedRows
    }

    private fun checkSubjectMutationAllowed(scope: UploadBufferScope) {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(
                "SELECT chronicle_participant_mutation_allowed(?, ?)",
            ).use { statement ->
                statement.setObject(1, scope.studyId)
                statement.setString(2, scope.participantId)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next()) { "Deletion mutation guard returned no result" }
                    check(resultSet.getBoolean(1)) {
                        "Upload-buffer flush is blocked by an erasure operation"
                    }
                }
            }
        }
    }

    private data class UploadBufferScope(
        val studyId: UUID,
        val participantId: String,
    )

    /**
     * This filters out events that have a null date logged and handles both String date time times from legacy events
     * and typed OffsetDateTime objects from non-legacy events.
     */
    private fun filter(mappedData: Sequence<Map<String, UsageEventColumn>>): Sequence<Map<String, UsageEventColumn>> {
        return mappedData.filter { mappedUsageEventCols ->
            val eventDate = mappedUsageEventCols[FQNS_TO_COLUMNS.getValue(DATE_LOGGED_FQN).name]?.value
            val dateLogged = odtFromUsageEventColumn(eventDate)

            val appPackageName = checkNotNull(mappedUsageEventCols[APP_PACKAGE_NAME.name]?.value as? String) {
                "Application package name cannot be null."
            }

            dateLogged != null && !appPackageName.contains("[")
        }
    }

    private fun <T> getUsageEventColumn(
        pcd: PostgresColumnDefinition,
        selector: () -> T,
    ): Pair<String, UsageEventColumn> {
        return pcd.name to UsageEventColumn(pcd.name, pcd.datatype, getInsertUsageEventColumnIndex(pcd), selector())
    }

    private fun mapToStorageModel(data: List<ChronicleUsageEvent>): Sequence<Map<String, UsageEventColumn>> {
        return data.asSequence().map { usageEvent ->
            mapOf(
//                getUsageEventColumn(STUDY_ID) { usageEvent.studyId },
//                getUsageEventColumn(PARTICIPANT_ID) { usageEvent.participantId },
                getUsageEventColumn(APP_PACKAGE_NAME) { usageEvent.appPackageName },
                getUsageEventColumn(ACTIVITY_CLASS) { usageEvent.activityClass },
                getUsageEventColumn(INTERACTION_TYPE) { usageEvent.interactionType },
                getUsageEventColumn(EVENT_TYPE) { usageEvent.eventType },
                getUsageEventColumn(TIMESTAMP) { usageEvent.timestamp },
                getUsageEventColumn(TIMEZONE) { usageEvent.timezone },
                getUsageEventColumn(USERNAME) { usageEvent.user },
                getUsageEventColumn(APPLICATION_LABEL) { usageEvent.applicationLabel },
                getUsageEventColumn(COLLECTED_AT) { usageEvent.collectedAt }
            )
        }
    }

    private fun mapLegacyDataToStorageModel(data: List<SetMultimap<UUID, Any>>): Sequence<Map<String, UsageEventColumn>> {
        return data.asSequence().map { usageEvent ->
            val usageEventCols = USAGE_EVENT_COLUMNS.associateTo(mutableMapOf()) { fqn ->
                val col = FQNS_TO_COLUMNS.getValue(fqn)
                val colIndex = getInsertUsageEventColumnIndex(col)
                val ptId = LegacyEdmResolver.getPropertyTypeId(fqn)
                val value = usageEvent[ptId].firstOrNull()
                col.name to UsageEventColumn(col.name, col.datatype, colIndex, value)
            }

            //Compute event type column for legacy clients.
            val col = EVENT_TYPE
            val colIndex = getInsertUsageEventColumnIndex(col)
            val value = fromInteractionType((usageEventCols[INTERACTION_TYPE.name]?.value ?: "None") as String)
            usageEventCols[col.name] = UsageEventColumn(col.name, col.datatype, colIndex, value)
            usageEventCols[ACTIVITY_CLASS.name] = UsageEventColumn(
                ACTIVITY_CLASS.name,
                ACTIVITY_CLASS.datatype,
                getInsertUsageEventColumnIndex(ACTIVITY_CLASS),
                null
            )
            usageEventCols
        }
    }

    // reason: batched-insert + dedup-merge over a single JDBC transaction with min/max timestamp
    // tracking and per-column type dispatch; the two boundary catches log and rethrow any failure
    // type, and splitting the body would break the shared connection/parameter-index invariants
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "TooGenericExceptionCaught")
    private fun writeToEventStorage(
        hds: HikariDataSource,
        data: List<UsageEventQueueEntry>,
        includeOnConflict: Boolean = false,
    ): Int {
        if (data.isEmpty()) return 0

        return hds.connection.use { connection ->
            //Create the temporary merge table
            try {
                var minEventTimestamp: OffsetDateTime = OffsetDateTime.MAX
                var maxEventTimestamp: OffsetDateTime = OffsetDateTime.MIN

                val studies = data.map { it.studyId.toString() }.toSet()
                val participants = data.map { it.participantId }.toSet()

                // Use a fixed batch size to stay below the Postgres bind-parameter limit.

                val insertBatchSize = min(data.size, EVENT_INSERT_BATCH_SIZE)
                logger.info("Preparing primary insert statement with batch size $insertBatchSize")
                val insertSql = buildMultilineInsertUsageEvents(
                    insertBatchSize,
                    includeOnConflict
                )

                val dr = data.size % EVENT_INSERT_BATCH_SIZE

                val finalInsertSql = if (data.size > EVENT_INSERT_BATCH_SIZE && dr != 0) {
                    logger.info("Preparing secondary insert statement with batch size $dr")
                    buildMultilineInsertUsageEvents(
                        dr,
                        includeOnConflict
                    )
                } else {
                    insertSql
                }

                val wc = data.chunked(EVENT_INSERT_BATCH_SIZE).sumOf { subList ->
                    logger.info("Processing sublist of length ${subList.size}")
                    connection.prepareStatement(if (subList.size == insertBatchSize) insertSql else finalInsertSql)
                        .use { ps ->

                            //Should only need to set these once for prepared statement.
                            StopWatch(
                                log = "Inserting ${data.size} entries into ${CHRONICLE_USAGE_EVENTS.name} with studies = {} and participants = {}",
                                level = Level.INFO,
                                logger = logger,
                                studies,
                                participants
                            ).use {
                                var indexBase = 0
                                subList.forEach { usageEventCols ->
                                    ps.setString(indexBase + 1, usageEventCols.studyId.toString())
                                    ps.setString(indexBase + 2, usageEventCols.participantId)
                                    usageEventCols.data.values.forEach { usageEventCol ->
                                        // If columns change, a lookup for colIndex by name would be needed here.
                                        val colIndex = indexBase + usageEventCol.colIndex
                                        val value = usageEventCol.value

                                        try {
                                            //Set insert value to null, if value was not provided.
                                            if (value == null) {
                                                ps.setObject(colIndex, null)
                                            } else {
                                                when (usageEventCol.datatype) {
                                                    PostgresDatatype.TEXT -> ps.setString(colIndex, value as String)
                                                    PostgresDatatype.TIMESTAMPTZ -> {
                                                        val odt = odtFromUsageEventColumn(value)
                                                        ps.setObject(
                                                            colIndex,
                                                            odt
                                                        )
                                                        //We need to keep track the min and max event timestamps for this batch
                                                        if (odt != null && usageEventCol.name == TIMESTAMP.name) {
                                                            if (odt.isBefore(minEventTimestamp)) {
                                                                minEventTimestamp = odt
                                                            }
                                                            if (odt.isAfter(maxEventTimestamp)) {
                                                                maxEventTimestamp = odt
                                                            }
                                                        }
                                                    }

                                                    PostgresDatatype.INTEGER -> ps.setInt(colIndex, value as Int)
                                                    PostgresDatatype.BIGINT -> ps.setLong(colIndex, value as Long)
                                                    else -> ps.setObject(colIndex, value)
                                                }
                                            }
                                        } catch (ex: Exception) {
                                            logger.error("Error writing $usageEventCol", ex)
                                            throw ex
                                        }
                                    }
                                    ps.setObject(indexBase + UPLOAD_AT_INDEX, usageEventCols.uploadedAt)
                                    // Explicit collected_at bind: buffered entries serialized
                                    // before this column existed have no map entry for it, and
                                    // pre-collected_at clients send null — both fall back to the
                                    // server receipt time rather than inventing a collection time.
                                    val collectedAt =
                                        odtFromUsageEventColumn(usageEventCols.data[COLLECTED_AT.name]?.value)
                                    ps.setObject(
                                        indexBase + COLLECTED_AT_INDEX,
                                        collectedAt ?: usageEventCols.uploadedAt
                                    )
                                    indexBase += CHRONICLE_USAGE_EVENTS.columns.size
//                                    logger.info(
//                                        "Added batch for ${ChronicleServerUtil.STUDY_PARTICIPANT}",
//                                        usageEventCols.studyId,
//                                        usageEventCols.participantId
//                                    )

                                }

                                StopWatch(
                                    log = "Executing update on ${subList.size} entries into " +
                                        "${CHRONICLE_USAGE_EVENTS.name} with studies = {} and participantRefs = {}",
                                    level = Level.INFO,
                                    logger = logger,
                                    studies,
                                    LogSanitizer.stableFingerprints(participants, "participant")
                                ).use {
                                    val insertCount = ps.executeUpdate()
                                    logger.info(
                                        "Inserted $insertCount entities for ${CHRONICLE_USAGE_EVENTS.name} studies = {}, participantRefs = {}",
                                        studies,
                                        LogSanitizer.stableFingerprints(participants, "participant")
                                    )
                                    insertCount
                                }
                            }

                        }
                }


//                StopWatch(
//                    log = "Merging entries for $tempInsertTableName with studies = {} and participants = {}",
//                    level = Level.INFO,
//                    logger = logger,
//                    studies,
//                    participants
//                ).use {
//                    connection.createStatement().use { stmt ->
//                        stmt.execute(getAppendTempTableSql(tempInsertTableName));
//                        stmt.execute("DROP TABLE $tempInsertTableName")
//                    }
//                }
//
                @Suppress("DEPRECATION") val tempTableName = SqlIdentifierValidator.validateTempTableName(
                    "duplicate_events_${RandomStringUtils.randomAlphanumeric(10)}"
                )


                //Create a table that contains any duplicate values introduced by this latest upload for the minimum upload_at value
                StopWatch(
                    log = "Creating duplicates table for studies = {} and participants = {} ",
                    level = Level.INFO,
                    logger = logger,
                    studies,
                    participants
                ).use {
                    connection.createStatement()
                        .use { stmt -> stmt.execute(createTempTableOfDuplicates(tempTableName)) }
                    connection.prepareStatement(buildTempTableOfDuplicates(tempTableName)).use { ps ->
                        ps.setArray(1, PostgresArrays.createTextArray(connection, studies))
                        ps.setArray(2, PostgresArrays.createTextArray(connection, participants))
                        ps.setObject(3, minEventTimestamp)
                        ps.setObject(4, maxEventTimestamp)
                        ps.execute()
                    }
                }

                //Delete the duplicates, if any from chronicle_usage_events and drop the temporary table.
                StopWatch(
                    log = "Deleting duplicates for studies = {} and participants = {} ",
                    level = Level.INFO,
                    logger = logger,
                    studies,
                    participants
                ).use {
                    connection.createStatement().use { stmt ->
                        stmt.execute(getDeleteUsageEventsFromTempTable(tempTableName))
                        stmt.execute("DROP TABLE $tempTableName")
                    }
                }

                data.groupBy { it.studyId to it.participantId }.forEach { (key, qe) ->
                    val (studyId, participantId) = key
                    updateParticipantStats(qe.map { it.data }, studyId, participantId)
                }

                return@use wc
            } catch (ex: Exception) {
                logger.error("Unable to save data to Postgres event storage.", ex)
                throw ex
            }
        }
    }


    private fun updateParticipantStats(
        data: List<Map<String, UsageEventColumn>>,
        studyId: UUID,
        participantId: String,
    ) {
        // unique dates
        val dates = data
            .mapNotNull {
                zdtFromAndroidColumns(
                    it.getValue(TIMESTAMP.name).value,
                    it.getValue(TIMEZONE.name).value as String
                )
            }
            .toMutableSet()

        if (dates.isEmpty()) {
            logger.info(
                "No valid dates found for studyId={}, participantRef={} — skipping stats update",
                studyId,
                LogSanitizer.stableFingerprint(participantId, "participant"),
            )
            return
        }

        val uniqueDates = dates.map { it.toLocalDate() }.toMutableSet()
        val minDate = dates.min().toOffsetDateTime()
        val maxDate = dates.max().toOffsetDateTime()

        val participantStats = ParticipantStats(
            studyId = studyId,
            participantId = participantId,
            androidLastPing = OffsetDateTime.now(),
            androidUniqueDates = uniqueDates,
            androidFirstDate = minDate,
            androidLastDate = maxDate,
        )
        studyManager.insertOrUpdateParticipantStats(participantStats)
    }
}

/**
 * Executes [block] only when a permit is acquired and releases exactly that permit.
 *
 * Keeping acquisition outside the try/finally prevents a failed non-blocking acquire from
 * inflating the semaphore, which would silently remove the intended drain concurrency bound.
 */
internal fun Semaphore.tryRunWithPermit(block: () -> Unit): Boolean {
    if (!tryAcquire()) {
        return false
    }
    try {
        block()
        return true
    } finally {
        release()
    }
}

public data class UsageEventQueueEntries(
    val studyId: UUID,
    val participantId: String,
    val data: List<Map<String, UsageEventColumn>>,
    val uploadedAt: OffsetDateTime,
) {
    public fun toEntryList(): List<UsageEventQueueEntry> {
        return data.map { UsageEventQueueEntry(studyId, participantId, it, uploadedAt) }
    }
}

public data class UsageEventQueueEntry(
    val studyId: UUID,
    val participantId: String,
    val data: Map<String, UsageEventColumn>,
    val uploadedAt: OffsetDateTime,
)

public data class UsageEventColumn(
    val name: String,
    val datatype: PostgresDatatype,
    val colIndex: Int,
    val value: Any?,
)

private val USAGE_EVENT_COLUMNS = listOf(
    FULL_NAME_FQN,
    RECORD_TYPE_FQN,
    DATE_LOGGED_FQN,
    TIMEZONE_FQN,
    USER_FQN,
    TITLE_FQN
)
