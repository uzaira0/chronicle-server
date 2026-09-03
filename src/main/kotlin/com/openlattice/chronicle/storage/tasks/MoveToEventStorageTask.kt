package com.openlattice.chronicle.storage.tasks

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.postgres.PostgresArrays
import com.geekbeast.postgres.PostgresDatatype
import com.geekbeast.tasks.HazelcastFixedRateTask
import com.geekbeast.tasks.Task
import com.geekbeast.util.StopWatch
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.openlattice.chronicle.postgres.ResultSetAdapters
import com.openlattice.chronicle.services.upload.UploadType
import com.openlattice.chronicle.services.upload.UsageEventQueueEntry
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.PostgresEventColumns
import com.openlattice.chronicle.storage.PostgresEventTables
import com.openlattice.chronicle.storage.odtFromUsageEventColumn
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.util.SqlIdentifierValidator
import com.zaxxer.hikari.HikariDataSource
import org.apache.commons.lang3.RandomStringUtils
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.security.InvalidParameterException
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.min

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public open class MoveToEventStorageTask : HazelcastFixedRateTask<MoveToEventStorageTaskDependencies> {
    internal companion object {
        // Derived so column growth can never push a full batch past the bind-parameter ceiling.
        private val EVENT_INSERT_BATCH_SIZE =
            (ChroniclePostgresTables.MAX_BIND_PARAMETERS - 1) /
                PostgresEventTables.CHRONICLE_USAGE_EVENTS.columns.size
        private const val PERIOD = 5*60000L
        private val UPLOAD_AT_INDEX = PostgresEventTables.getInsertUsageEventColumnIndex(PostgresEventColumns.UPLOADED_AT)
        private val COLLECTED_AT_INDEX = PostgresEventTables.getInsertUsageEventColumnIndex(PostgresEventColumns.COLLECTED_AT)
        private val logger = LoggerFactory.getLogger(MoveToEventStorageTask::class.java)

        private val executor: ListeningExecutorService =
            MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3))
    }

    // reason: boundary catch — scheduled task must not let any failure type escape the run loop
    @Suppress("TooGenericExceptionCaught")
    override fun runTask() {
        val f = executor.submit {
            RLSRequestContext.withSystemContext {
                moveToEventStorage()
            }
        }
        try {
            f.get(1, TimeUnit.HOURS)
        } catch (timeoutException: TimeoutException) {
            logger.error("Timed out after one hour when moving events to event storage.", timeoutException)
            f.cancel(true)
        } catch (ex: Exception) {
            logger.error("Exception when moving events to event storage.", ex)
        }
    }

    override fun getName(): String = Task.MOVE_TO_EVENT_STORAGE.name

    // reason: boundary catch — log-and-rethrow guard around the full DB move; NestedBlockDepth is
    // inherent to the connection/statement/resultset use{} nesting and refactoring risks the txn scope
    @Suppress("TooGenericExceptionCaught", "NestedBlockDepth")
    private fun moveToEventStorage() {
        with(getDependency()) {
            try {
                logger.info("Moving data from the Postgres upload buffer to event storage.")
                val queueEntriesByFlavor: MutableMap<PostgresFlavor, MutableList<UsageEventQueueEntry>> = mutableMapOf()
                storageResolver.getPlatformStorage().connection.use { platform ->
                    platform.autoCommit = false
                    platform.createStatement().use { stmt ->
                        stmt.executeQuery(ChroniclePostgresTables.getMoveSql(128, UploadType.Android)).use { rs ->
                            while (rs.next()) {
                                val usageEventQueueEntries = ResultSetAdapters.usageEventQueueEntries(rs)
                                val (flavor, _) = storageResolver.resolveAndGetFlavor(usageEventQueueEntries.studyId)
                                queueEntriesByFlavor.getOrPut(flavor) { mutableListOf() }
                                    .addAll(usageEventQueueEntries.toEntryList())
                            }
                        }
                        val vanillaEntryCount = (queueEntriesByFlavor[PostgresFlavor.VANILLA] ?: listOf()).size
                        logger.info("Total number of entries for Postgres event storage: $vanillaEntryCount")
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
                    }
                    platform.commit()
                    platform.autoCommit = true
                }
                logger.info("Successfully moved data to event storage.")
            } catch (ex: Exception) {
                logger.error("Unable to move data from the Postgres upload buffer to event storage.", ex)
                throw ex
            }
        }
    }

    // reason: batched JDBC insert path with min/max timestamp tracking, duplicate-purge and
    // prepared-statement reuse; length/complexity/nesting are inherent to the transactional SQL
    // pipeline and the boundary catches log-and-rethrow per-column/per-batch failures — restructuring
    // risks the bind-parameter limits and transaction semantics
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
                //Future: May base this off data being inserted instead?
                var minEventTimestamp: OffsetDateTime = OffsetDateTime.MAX
                var maxEventTimestamp: OffsetDateTime = OffsetDateTime.MIN

                val studies = data.map { it.studyId.toString() }.toSet()
                val participants = data.map { it.participantId }.toSet()

                // Use fixed-size prepared statements to stay below the Postgres bind-parameter limit.

                val insertBatchSize = min(data.size, EVENT_INSERT_BATCH_SIZE)
                logger.info("Preparing primary insert statement with batch size $insertBatchSize")
                val insertSql = PostgresEventTables.buildMultilineInsertUsageEvents(
                    insertBatchSize,
                    includeOnConflict
                )

                val dr = data.size % EVENT_INSERT_BATCH_SIZE

                val finalInsertSql = if (data.size > EVENT_INSERT_BATCH_SIZE && dr != 0) {
                    logger.info("Preparing secondary insert statement with batch size $dr")
                    PostgresEventTables.buildMultilineInsertUsageEvents(
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
                                log = "Inserting ${data.size} entries into " +
                                    "${PostgresEventTables.CHRONICLE_USAGE_EVENTS.name} " +
                                    "with studies = {} and participants = {}",
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
                                        //Note: If we ever change the columns, we need to do a lookup for colIndex by name every time.
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
                                                        if (odt != null && usageEventCol.name == PostgresEventColumns.TIMESTAMP.name) {
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
                                    val collectedAt = odtFromUsageEventColumn(
                                        usageEventCols.data[PostgresEventColumns.COLLECTED_AT.name]?.value
                                    )
                                    ps.setObject(
                                        indexBase + COLLECTED_AT_INDEX,
                                        collectedAt ?: usageEventCols.uploadedAt
                                    )
                                    indexBase += PostgresEventTables.CHRONICLE_USAGE_EVENTS.columns.size
//                                    logger.info(
//                                        "Added batch for ${ChronicleServerUtil.STUDY_PARTICIPANT}",
//                                        usageEventCols.studyId,
//                                        usageEventCols.participantId
//                                    )

                                }

                                StopWatch(
                                    log = "Executing update on ${subList.size} entries into " +
                                        "${PostgresEventTables.CHRONICLE_USAGE_EVENTS.name} " +
                                        "with studies = {} and participants = {}",
                                    level = Level.INFO,
                                    logger = logger,
                                    studies,
                                    participants
                                ).use {
                                    val insertCount = ps.executeUpdate()
                                    logger.info(
                                            "Inserted $insertCount entities for " +
                                            "${PostgresEventTables.CHRONICLE_USAGE_EVENTS.name} " +
                                            "studies = {}, participantRefs = {}",
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
                        .use { stmt -> stmt.execute(PostgresEventTables.createTempTableOfDuplicates(tempTableName)) }
                    connection.prepareStatement(PostgresEventTables.buildTempTableOfDuplicates(tempTableName))
                        .use { ps ->
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
                        stmt.execute(PostgresEventTables.getDeleteUsageEventsFromTempTable(tempTableName))
                        stmt.execute("DROP TABLE $tempTableName")
                    }
                }

                return@use wc
            } catch (ex: Exception) {
                logger.error("Unable to save data to Postgres event storage.", ex)
                throw ex
            }
        }
    }
    override fun getInitialDelay(): Long = PERIOD

    override fun getPeriod(): Long = PERIOD

    override fun getTimeUnit(): TimeUnit = TimeUnit.MILLISECONDS

    override fun getDependenciesClass(): Class<out MoveToEventStorageTaskDependencies> =
        MoveToEventStorageTaskDependencies::class.java


}
