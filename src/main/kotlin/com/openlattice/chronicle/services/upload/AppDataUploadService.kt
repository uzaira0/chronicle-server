package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.storage.withTransaction
import com.openlattice.chronicle.storage.ParticipantCollectionGate

import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.postgres.PostgresColumnDefinition
import com.geekbeast.postgres.PostgresDatatype
import com.geekbeast.util.StopWatch
import com.openlattice.chronicle.postgres.postgresText
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.participants.ParticipantStats
import com.openlattice.chronicle.postgres.ResultSetAdapters
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.UPLOAD_BUFFER
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.getMoveSql
import com.openlattice.chronicle.storage.PostgresColumns
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UPLOAD_TYPE
import com.openlattice.chronicle.storage.PostgresColumns.Companion.UPLOAD_DATA
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APPLICATION_LABEL
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APP_PACKAGE_NAME
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.ACTIVITY_CLASS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.CLIENT_EVENT_ID
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.EVENT_TYPE
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
    private val studyManager: StudyManager,
) : AppDataUploadManager {
    internal companion object {
        // A btree entry holds at most 2704 bytes on 8 kB pages; the rest covers tuple headers and
        // the fixed-width key columns. Applies to the identity and activity_class indexes.
        const val MAX_USAGE_EVENT_KEY_TEXT_BYTES = 2048
        private val logger = LoggerFactory.getLogger(AppDataUploadService::class.java)
        private val mapper = ObjectMappers.getJsonMapper()
        private val semaphore = Semaphore(10)
        private const val MOVE_BATCH_SIZE = 128


        /**
         * 1. study id
         * 2. participant id
         * 3. upload data
         * 4. uploaded at
         * 5. random device id
         */
        private val INSERT_USAGE_EVENTS_SQL = """
                    INSERT INTO ${UPLOAD_BUFFER.name} (${STUDY_ID.name},${PARTICIPANT_ID.name},${UPLOAD_DATA.name}, ${PostgresColumns.UPLOADED_AT.name}, ${UPLOAD_TYPE.name}, ${PostgresColumns.RANDOM_DEVICE_ID.name})
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
        randomDeviceId: UUID,
        data: List<ChronicleUsageEvent>,
        uploadedAt: OffsetDateTime,
    ): Set<UUID> {
        requireUploadBatchSize(data.size)
        require('\u0000' !in participantId) { "Participant id contains an unsupported character" }
        val normalizedData = data.map { event ->
            event.copy(appPackageName = event.appPackageName.postgresText(),
                activityClass = event.activityClass?.postgresText(), interactionType = event.interactionType.postgresText(),
                user = event.user.postgresText(), applicationLabel = event.applicationLabel.postgresText())
        }
        normalizedData.forEach { event ->
            requireUploadRow(listOf(event.timezone, event.participantId).filterNotNull().none { '\u0000' in it }) {
                "Upload text contains an unsupported character"
            }
            // A zone id this server's time-zone data does not know (a newer phone's) is stored as given.
            requireUploadRow(listOf(event.timestamp, uploadedAt).all { it.year in 1..9999 }) {
                "Upload timestamp is outside the supported range"
            }
        }
        val participantRef = LogSanitizer.stableFingerprint(participantId, "participant")
        timedUpload(studyId, participantRef, randomDeviceId, data.size) {
            try {
                storageResolver.resolveAndGetFlavor(studyId)

                // The sole production caller is StudyController, whose mobile-upload gate owns
                // participation and datasource authorization and maps every rejection to HTTP 403.
                // Rechecking here added two hot-path queries and could race into a divergent 500.

                logger.info(
                    "attempting to log data - studyRef = {}, participantRef = {}, deviceRef = {}",
                    LogSanitizer.stableFingerprint(studyId.toString(), "study"),
                    participantRef,
                    LogSanitizer.stableFingerprint(randomDeviceId.toString(), "device"),
                )

                val accepted = withoutUnstorableEvents(studyId, participantId, participantRef, normalizedData)
                // Malformed rows, the capture cutoff and erasure drop events; only the stored ones are reported back.
                val stored = doWrite(studyId, participantId, randomDeviceId, mapToStorageModel(accepted), data.size, uploadedAt)

                // Record upload metrics
                ChronicleMetrics.uploadTotal.labels("android_usage").inc()

                return stored
            } catch (exception: Exception) {
                logger.error(
                    "error logging data - studyRef = {}, participantRef = {}, deviceRef = {}",
                    LogSanitizer.stableFingerprint(studyId.toString(), "study"),
                    participantRef,
                    LogSanitizer.stableFingerprint(randomDeviceId.toString(), "device"),
                    exception
                )
                ChronicleMetrics.uploadErrors.labels("android_usage", exception.javaClass.simpleName).inc()
                throw exception
            }
        }
    }


    private fun doWrite(
        studyId: UUID,
        participantId: String,
        randomDeviceId: UUID,
        mappedData: Sequence<Map<String, UsageEventColumn>>,
        expectedSize: Int,
        uploadedAt: OffsetDateTime,
    ): Set<UUID> {
        val dataList = mappedData.toList()
        StopWatch(
            log = "Writing ${dataList.size} entites (expected: $expectedSize) to Postgres upload buffer",
            level = Level.INFO,
            logger = logger,
        ).use {
            val platformStorage = storageResolver.getPlatformStorage()
            return platformStorage.connection.use { connection ->
                connection.withTransaction {
                    PinnedPlatformConnection.committing(platformStorage, connection) {
                    DeletionStudyFence.shared(connection, studyId)
                    ParticipantCollectionGate(connection).requireCollectionAvailable(studyId, participantId)
                    val queued = dataList.map { UsageEventQueueEntry(studyId, participantId, it, uploadedAt) }
                    val retainedData = ErasedUsageEventFilter.retainPermitted(connection, queued).map { it.data }
                    if (retainedData.isNotEmpty()) connection.prepareStatement(INSERT_USAGE_EVENTS_SQL).use { ps ->
                        ps.setObject(1, studyId)
                        ps.setString(2, participantId)
                        ps.setString(3, mapper.writeValueAsString(retainedData))
                        ps.setObject(4, uploadedAt)
                        ps.setObject(5, randomDeviceId)
                        ps.executeUpdate()
                    }
                    if (retainedData.isNotEmpty()) updateParticipantStats(retainedData, studyId, participantId, uploadedAt)
                    retainedData.mapNotNullTo(linkedSetOf()) { row ->
                        (row[CLIENT_EVENT_ID.name]?.value as? String)?.let(UUID::fromString)
                    }
                    }
                }
            }
        }
    }

    private inline fun <T> timedUpload(studyId: UUID, participantRef: String, randomDeviceId: UUID, entries: Int, body: () -> T): T =
        StopWatch(
            log = "logging $entries entries for studyRef = {}, participantRef = {}, deviceRef = {}",
            level = Level.INFO,
            logger = logger,
            LogSanitizer.stableFingerprint(studyId.toString(), "study"),
            participantRef,
            LogSanitizer.stableFingerprint(randomDeviceId.toString(), "device"),
        ).use { body() }

    // reason: boundary catch — transactional buffer-to-storage move must log and rethrow any
    // failure type; the nested result-set iteration over the transaction can't be extracted safely
    @Suppress("TooGenericExceptionCaught", "NestedBlockDepth")
    override fun moveToEventStorage() {
        try {
            semaphore.tryRunWithPermit {
                moveToEventStorageBatch()
            }
        } catch (ex: Exception) {
            logger.error("Unable to move data from the Postgres upload buffer to event storage.", ex)
            throw ex
        }
    }

    /**
     * Test hook only: drains every buffered Android usage row before returning. Each batch first takes the drain
     * table locks ([DeletionTableLockOrder.lockDrain]), so a background drain already holding rows commits first.
     */
    public fun drainEventStorage() {
        semaphore.acquire()
        try {
            do { val claimed = moveToEventStorageBatch() } while (claimed > 0)
        } finally {
            semaphore.release()
        }
    }

    @Suppress("TooGenericExceptionCaught", "NestedBlockDepth")
    private fun moveToEventStorageBatch(): Int {
        logger.info("Moving data from the Postgres upload buffer to event storage.")
        var claimedRows = 0
        val platformStorage = storageResolver.getPlatformStorage()
        storageResolver.requireDefaultDeletionStorageColocated()
        platformStorage.connection.use { platform ->
            platform.withTransaction {
                PinnedPlatformConnection.committing(platformStorage, platform) {
                val fencedStudies = DeletionTableLockOrder.lockDrain(platform, "chronicle_usage_events", MOVE_BATCH_SIZE)
                platform.prepareStatement(getMoveSql(MOVE_BATCH_SIZE, UploadType.Android, fencedStudies)).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        while (resultSet.next()) {
                            claimedRows += 1
                            ResultSetAdapters.forEachUsageEventChunk(resultSet) { entries ->
                                val flavor = storageResolver.resolveAndGetFlavor(entries.first().studyId).flavor
                                UsageEventStorageWriter.writeByFlavor(storageResolver, mapOf(flavor to entries), logger) { written ->
                                    written.groupBy { it.studyId to it.participantId }.forEach { (key, values) ->
                                        updateParticipantStats(values.map { it.data }, key.first, key.second,
                                            values.maxOf { it.uploadedAt })
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        }
        logger.info("Successfully moved {} upload-buffer row(s) to event storage.", claimedRows)
        return claimedRows
    }

    /**
     * Drops events that could never be stored, counting and logging each discard: a package name
     * holding '[', or key text too large for the table's btree indexes (it would fail every drain).
     */
    private fun withoutUnstorableEvents(
        studyId: UUID,
        participantId: String,
        participantRef: String,
        events: List<ChronicleUsageEvent>,
    ): List<ChronicleUsageEvent> {
        fun bytes(vararg values: String?) = values.sumOf { it?.toByteArray(Charsets.UTF_8)?.size ?: 0 }
        val subjectBytes = bytes(studyId.toString(), participantId)
        val (malformed, wellFormed) = events.partition { '[' in it.appPackageName }
        val (accepted, oversized) = wellFormed.partition {
            // chronicle_usage_events_identity (V1) indexes all of these text fields in one key.
            subjectBytes + bytes(it.appPackageName, it.activityClass, it.interactionType, it.timezone, it.user,
                it.applicationLabel) <= MAX_USAGE_EVENT_KEY_TEXT_BYTES
        }
        for ((discarded, reason) in listOf(malformed to "malformed_package_name", oversized to "index_key_too_large")) {
            if (discarded.isEmpty()) continue
            logger.error(
                "discarded {} usage events ({}) - studyRef = {}, participantRef = {}",
                discarded.size,
                reason,
                LogSanitizer.stableFingerprint(studyId.toString(), "study"),
                participantRef,
            )
            ChronicleMetrics.uploadErrors.labels("android_usage", reason).inc(discarded.size.toDouble())
        }
        return accepted
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
                getUsageEventColumn(CLIENT_EVENT_ID) { usageEvent.clientEventId?.toString() },
            )
        }
    }


    private fun updateParticipantStats(
        data: List<Map<String, UsageEventColumn>>,
        studyId: UUID,
        participantId: String,
        uploadedAt: OffsetDateTime,
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
            androidLastPing = uploadedAt,
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
