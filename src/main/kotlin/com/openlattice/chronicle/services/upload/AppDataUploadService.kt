package com.openlattice.chronicle.services.upload

import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.postgres.PostgresColumnDefinition
import com.geekbeast.postgres.PostgresDatatype
import com.geekbeast.util.StopWatch
import com.google.common.collect.SetMultimap
import com.openlattice.chronicle.postgres.postgresText
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
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.USERNAME
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.getInsertUsageEventColumnIndex
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.UsageEventStorageWriter
import com.openlattice.chronicle.storage.DeletionStudyFence
import com.openlattice.chronicle.storage.ErasedUsageEventFilter
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.DeletionTableLockOrder
import com.openlattice.chronicle.storage.odtFromUsageEventColumn
import com.openlattice.chronicle.storage.zdtFromAndroidColumns
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.observability.ChronicleMetrics
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.OffsetDateTime
import java.util.*
import java.util.concurrent.Semaphore

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
        private val mapper = ObjectMappers.getJsonMapper()
        private val semaphore = Semaphore(10)
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
        require('\u0000' !in participantId) { "Participant id contains an unsupported character" }
        val normalizedData = data.map { event ->
            event.copy(appPackageName = event.appPackageName.postgresText(),
                activityClass = event.activityClass?.postgresText(), interactionType = event.interactionType.postgresText(),
                user = event.user.postgresText(), applicationLabel = event.applicationLabel.postgresText())
        }
        normalizedData.forEach { event ->
            require(listOf(event.timezone, event.participantId).filterNotNull().none { '\u0000' in it }) {
                "Upload text contains an unsupported character"
            }
            try {
                java.time.ZoneId.of(event.timezone)
            } catch (_: java.time.DateTimeException) {
                throw IllegalArgumentException("Upload time zone is invalid")
            }
            require(listOfNotNull(event.timestamp, event.collectedAt, uploadedAt).all { it.year in 1..9999 }) {
                "Upload timestamp is outside the supported range"
            }
        }
        val participantRef = LogSanitizer.stableFingerprint(participantId, "participant")
        timedUpload(studyId, participantRef, deviceId, data.size) {
            try {
                val (flavor, hds) = storageResolver.resolveAndGetFlavor(studyId)

                // The sole production caller is StudyController, whose mobile-upload gate owns
                // participation and datasource authorization and maps every rejection to HTTP 403.
                // Rechecking here added two hot-path queries and could race into a divergent 500.

                logger.info(
                    "attempting to log data - studyRef = {}, participantRef = {}, deviceRef = {}",
                    LogSanitizer.stableFingerprint(studyId.toString(), "study"),
                    participantRef,
                    LogSanitizer.stableFingerprint(deviceId.toString(), "device"),
                )

                val mappedData = filter(mapToStorageModel(normalizedData))
                val expectedSize = data.size
                // Events rejected by filter() (null date logged, or a package name containing '[')
                // are never persisted, so acknowledging data.size would tell the client that
                // records it must stop retrying were stored when they were not.
                val stored = doWrite(studyId, participantId, deviceId, mappedData, expectedSize, uploadedAt)

                // Record upload metrics
                ChronicleMetrics.uploadTotal.labels("android_usage").inc()

                return stored
            } catch (exception: Exception) {
                logger.error(
                    "error logging data - studyRef = {}, participantRef = {}, deviceRef = {}",
                    LogSanitizer.stableFingerprint(studyId.toString(), "study"),
                    participantRef,
                    LogSanitizer.stableFingerprint(deviceId.toString(), "device"),
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
        timedUpload(studyId, participantRef, deviceId, data.size) {
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
                        "data source not found, ignoring upload - studyRef = {}, participantRef = {}, deviceRef = {}",
                        LogSanitizer.stableFingerprint(studyId.toString(), "study"),
                        participantRef,
                        LogSanitizer.stableFingerprint(deviceId.toString(), "device"),
                    )
                    return 0
                }

                logger.info(
                    "attempting to log data - studyRef = {}, participantRef = {}, deviceRef = {}",
                    LogSanitizer.stableFingerprint(studyId.toString(), "study"),
                    participantRef,
                    LogSanitizer.stableFingerprint(deviceId.toString(), "device"),
                )

                val mappedData = filter(mapLegacyDataToStorageModel(data))
                val expectedSize = data.size

                return doWrite(studyId, participantId, deviceId, mappedData, expectedSize, uploadedAt)
            } catch (exception: Exception) {
                logger.error(
                    "error logging data - studyRef = {}, participantRef = {}, deviceRef = {}",
                    LogSanitizer.stableFingerprint(studyId.toString(), "study"),
                    participantRef,
                    LogSanitizer.stableFingerprint(deviceId.toString(), "device"),
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
        StopWatch(
            log = "Writing ${dataList.size} entites (expected: $expectedSize) to Postgres upload buffer",
            level = Level.INFO,
            logger = logger,
        ).use {
            val platformStorage = storageResolver.getPlatformStorage()
            platformStorage.connection.use { connection ->
                val originalAutoCommit = connection.autoCommit
                connection.autoCommit = false
                try {
                    PinnedPlatformConnection.committing(platformStorage, connection) {
                    DeletionStudyFence.shared(connection, studyId)
                    val captures = com.openlattice.chronicle.storage.ParticipantCollectionGate.retainQueuedCaptures(
                        connection, studyId, participantId, dataList,
                        { odtFromUsageEventColumn(it[TIMESTAMP.name]?.value) })
                    val queued = captures.map { UsageEventQueueEntry(studyId, participantId, it, uploadedAt) }
                    val retainedData = ErasedUsageEventFilter.retainPermitted(connection, queued).map { it.data }
                    if (retainedData.isNotEmpty()) connection.prepareStatement(INSERT_USAGE_EVENTS_SQL).use { ps ->
                        ps.setObject(1, studyId)
                        ps.setString(2, participantId)
                        ps.setString(3, mapper.writeValueAsString(retainedData))
                        ps.setObject(4, uploadedAt)
                        ps.setObject(5, deviceId)
                        ps.executeUpdate()
                    }
                    if (retainedData.isNotEmpty()) updateParticipantStats(retainedData, studyId, participantId)
                    }
                } catch (failure: Exception) {
                    connection.rollback()
                    throw failure
                } finally {
                    connection.autoCommit = originalAutoCommit
                }
            }
        }

        // We may write fewer entities than provided; we return the number processed so the client knows all is good.
        if (expectedSize != dataList.size) {
            //Should probably be an assertion as this should never happen.
            logger.warn("Wrote ${dataList.size} entities, but expected to write $expectedSize entities")
        }

        // Acknowledge handled events, including pre-purge rows intentionally discarded.
        // The prepared statement's update count is the number of buffer rows (always 1) and says
        // nothing about how many events survived filter().
        return dataList.size
    }

    private inline fun <T> timedUpload(studyId: UUID, participantRef: String, deviceId: UUID, entries: Int, body: () -> T): T =
        StopWatch(
            log = "logging $entries entries for studyRef = {}, participantRef = {}, deviceRef = {}",
            level = Level.INFO,
            logger = logger,
            LogSanitizer.stableFingerprint(studyId.toString(), "study"),
            participantRef,
            LogSanitizer.stableFingerprint(deviceId.toString(), "device"),
        ).use { body() }

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
                LogSanitizer.stableFingerprint(studyId.toString(), "study"),
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
        var claimedRows = 0
        val platformStorage = storageResolver.getPlatformStorage()
        storageResolver.requireDefaultDeletionStorageColocated()
        platformStorage.connection.use { platform ->
            val previousAutoCommit = platform.autoCommit
            platform.autoCommit = false
            try {
                PinnedPlatformConnection.committing(platformStorage, platform) {
                val fencedStudies = DeletionTableLockOrder.lockDrain(platform, "chronicle_usage_events", MOVE_BATCH_SIZE, scope?.studyId,
                    scope?.participantId)
                val moveSql = if (scope == null) {
                    getMoveSql(MOVE_BATCH_SIZE, UploadType.Android, fencedStudies)
                } else {
                    getScopedMoveSql(MOVE_BATCH_SIZE, UploadType.Android, fencedStudies)
                }
                platform.prepareStatement(moveSql).use { statement ->
                    if (scope != null) {
                        statement.setObject(1, scope.studyId)
                        statement.setString(2, scope.participantId)
                    }
                    statement.executeQuery().use { resultSet ->
                        while (resultSet.next()) {
                            claimedRows += 1
                            ResultSetAdapters.forEachUsageEventChunk(resultSet) { entries ->
                                val (flavor, _) = storageResolver.resolveAndGetFlavor(entries.first().studyId)
                                UsageEventStorageWriter.writeByFlavor(storageResolver, mapOf(flavor to entries), logger) { written ->
                                    written.groupBy { it.studyId to it.participantId }.forEach { (key, values) ->
                                        updateParticipantStats(values.map { it.data }, key.first, key.second)
                                    }
                                }
                            }
                        }
                    }
                }
                }
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
                val value = usageEvent[ptId].firstOrNull().let { if (it is String) it.postgresText() else it }
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
                "No valid dates found for studyRef={}, participantRef={} — skipping stats update",
                LogSanitizer.stableFingerprint(studyId.toString(), "study"),
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
