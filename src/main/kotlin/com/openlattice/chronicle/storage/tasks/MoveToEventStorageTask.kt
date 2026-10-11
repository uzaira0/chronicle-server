package com.openlattice.chronicle.storage.tasks

import com.geekbeast.tasks.HazelcastFixedRateTask
import com.geekbeast.tasks.Task
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.openlattice.chronicle.postgres.ResultSetAdapters
import com.openlattice.chronicle.services.upload.UploadType
import com.openlattice.chronicle.storage.ChroniclePostgresTables
import com.openlattice.chronicle.storage.UsageEventStorageWriter
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.DeletionTableLockOrder
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public open class MoveToEventStorageTask : HazelcastFixedRateTask<MoveToEventStorageTaskDependencies> {
    internal companion object {
        private const val PERIOD = 5*60000L
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
                val platformStorage = storageResolver.getPlatformStorage()
                storageResolver.requireDefaultDeletionStorageColocated()
                platformStorage.connection.use { platform ->
                    platform.autoCommit = false
                    PinnedPlatformConnection.committing(platformStorage, platform) {
                        val fencedStudies = DeletionTableLockOrder.lockDrain(platform, "chronicle_usage_events")
                        platform.createStatement().use { stmt ->
                            stmt.executeQuery(ChroniclePostgresTables.getMoveSql(128, UploadType.Android, fencedStudies)).use { rs ->
                                while (rs.next()) {
                                    ResultSetAdapters.forEachUsageEventChunk(rs) { entries ->
                                        val (flavor, _) = storageResolver.resolveAndGetFlavor(entries.first().studyId)
                                        UsageEventStorageWriter.writeByFlavor(storageResolver, mapOf(flavor to entries), logger)
                                    }
                                }
                            }
                        }
                    }
                    platform.autoCommit = true
                }
                logger.info("Successfully moved data to event storage.")
            } catch (ex: Exception) {
                logger.error("Unable to move data from the Postgres upload buffer to event storage.", ex)
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
