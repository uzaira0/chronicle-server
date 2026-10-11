package com.openlattice.chronicle.storage

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.postgres.PostgresArrays
import com.geekbeast.postgres.PostgresDatatype
import com.geekbeast.util.StopWatch
import com.openlattice.chronicle.services.upload.UsageEventQueueEntry
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.MAX_BIND_PARAMETERS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.COLLECTED_AT
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.TIMESTAMP
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.UPLOADED_AT
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.CHRONICLE_USAGE_EVENTS
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.buildMultilineInsertUsageEvents
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.buildTempTableOfDuplicates
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.createTempTableOfDuplicates
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.getDeleteUsageEventsFromTempTable
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.getInsertUsageEventColumnIndex
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.util.SqlIdentifierValidator
import com.zaxxer.hikari.HikariDataSource
import org.apache.commons.lang3.RandomStringUtils
import org.slf4j.Logger
import org.slf4j.event.Level
import java.security.InvalidParameterException
import java.time.OffsetDateTime
import kotlin.math.min

internal object UsageEventStorageWriter {
    private val UPLOAD_AT_INDEX = getInsertUsageEventColumnIndex(UPLOADED_AT)
    private val COLLECTED_AT_INDEX = getInsertUsageEventColumnIndex(COLLECTED_AT)
    // Column growth must keep full batches below the Postgres bind-parameter ceiling.
    private val EVENT_INSERT_BATCH_SIZE = (MAX_BIND_PARAMETERS - 1) / CHRONICLE_USAGE_EVENTS.columns.size

    fun writeByFlavor(
        storageResolver: StorageResolver,
        entriesByFlavor: Map<PostgresFlavor, List<UsageEventQueueEntry>>,
        logger: Logger,
        afterWrite: (List<UsageEventQueueEntry>) -> Unit = {},
    ) {
        entriesByFlavor.forEach { (flavor, entries) ->
            if (entries.isEmpty()) return@forEach
            when (flavor) {
                PostgresFlavor.VANILLA, PostgresFlavor.ANY -> write(
                    storageResolver.getEventStorageWithFlavor(PostgresFlavor.VANILLA), entries, logger, afterWrite,
                )
                else -> throw InvalidParameterException("Invalid postgres flavor: ${flavor.name}")
            }
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "TooGenericExceptionCaught")
    internal fun write(
        hds: HikariDataSource,
        queuedData: List<UsageEventQueueEntry>,
        logger: Logger,
        afterWrite: (List<UsageEventQueueEntry>) -> Unit,
    ): Int {
        if (queuedData.isEmpty()) return 0

        return PinnedPlatformConnection.resolve(hds).connection.use { connection ->
            val data = ErasedUsageEventFilter.retainPermitted(connection, queuedData)
            if (data.isEmpty()) return@use 0
            try {
                var minEventTimestamp: OffsetDateTime = OffsetDateTime.MAX
                var maxEventTimestamp: OffsetDateTime = OffsetDateTime.MIN

                val studies = data.map { it.studyId.toString() }.toSet()
                val participants = data.map { it.participantId }.toSet()

                val insertBatchSize = min(data.size, EVENT_INSERT_BATCH_SIZE)
                logger.info("Preparing primary insert statement with batch size $insertBatchSize")
                val insertSql = buildMultilineInsertUsageEvents(
                    insertBatchSize,
                    false
                )

                val dr = data.size % EVENT_INSERT_BATCH_SIZE

                val finalInsertSql = if (data.size > EVENT_INSERT_BATCH_SIZE && dr != 0) {
                    logger.info("Preparing secondary insert statement with batch size $dr")
                    buildMultilineInsertUsageEvents(
                        dr,
                        false
                    )
                } else {
                    insertSql
                }

                val wc = data.chunked(EVENT_INSERT_BATCH_SIZE).sumOf { subList ->
                    logger.info("Processing sublist of length ${subList.size}")
                    connection.prepareStatement(if (subList.size == insertBatchSize) insertSql else finalInsertSql)
                        .use { ps ->

                            StopWatch(
                                log = "Inserting ${data.size} entries into ${CHRONICLE_USAGE_EVENTS.name} with studies = {} and participantRefs = {}",
                                level = Level.INFO,
                                logger = logger,
                                studies,
                                LogSanitizer.stableFingerprints(participants, "participant")
                            ).use {
                                var indexBase = 0
                                subList.forEach { usageEventCols ->
                                    ps.setString(indexBase + 1, usageEventCols.studyId.toString())
                                    ps.setString(indexBase + 2, usageEventCols.participantId)
                                    usageEventCols.data.values.forEach { usageEventCol ->
                                        val colIndex = indexBase + usageEventCol.colIndex
                                        val value = usageEventCol.value

                                        try {
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
                                    // Legacy buffered rows fall back to receipt time when collected_at is absent.
                                    val collectedAt =
                                        odtFromUsageEventColumn(usageEventCols.data[COLLECTED_AT.name]?.value)
                                    ps.setObject(
                                        indexBase + COLLECTED_AT_INDEX,
                                        collectedAt ?: usageEventCols.uploadedAt
                                    )
                                    indexBase += CHRONICLE_USAGE_EVENTS.columns.size

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

                @Suppress("DEPRECATION") val tempTableName = SqlIdentifierValidator.validateTempTableName(
                    "duplicate_events_${RandomStringUtils.randomAlphanumeric(10)}"
                )

                StopWatch(
                    log = "Creating duplicates table for studies = {} and participantRefs = {} ",
                    level = Level.INFO,
                    logger = logger,
                    studies,
                    LogSanitizer.stableFingerprints(participants, "participant")
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

                StopWatch(
                    log = "Deleting duplicates for studies = {} and participantRefs = {} ",
                    level = Level.INFO,
                    logger = logger,
                    studies,
                    LogSanitizer.stableFingerprints(participants, "participant")
                ).use {
                    connection.createStatement().use { stmt ->
                        stmt.execute(getDeleteUsageEventsFromTempTable(tempTableName))
                        stmt.execute("DROP TABLE $tempTableName")
                    }
                }

                afterWrite(data)

                return@use wc
            } catch (ex: Exception) {
                logger.error("Unable to save data to Postgres event storage.", ex)
                throw ex
            }
        }
    }

}
