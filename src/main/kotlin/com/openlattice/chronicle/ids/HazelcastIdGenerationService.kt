package com.openlattice.chronicle.ids

import com.geekbeast.hazelcast.IHazelcastClientProvider
import com.google.common.collect.Queues
import com.openlattice.chronicle.hazelcast.HazelcastClient
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.hazelcast.HazelcastQueue
import com.openlattice.chronicle.mapstores.ids.IdsGeneratingEntryProcessor
import com.openlattice.chronicle.mapstores.ids.Range
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.*
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue

/**
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 * @param random issue random UUIDs. The sequential mode hands out consecutive ids per partition,
 * so a study id predicts the next one; it is kept for tests.
 * Set in the constructor so the producer thread started by `init` never sees the default.
 */
public open class HazelcastIdGenerationService(
    clients: IHazelcastClientProvider,
    private val random: Boolean = false,
) {

    /*
     * This should be good enough until we scale past 65536 Hazelcast nodes.
     */
    internal companion object {
        private const val PARTITION_SCROLL_SIZE = 5
        private const val MASK_LENGTH = 16
        public const val NUM_PARTITIONS = 1 shl MASK_LENGTH //65536
        private val logger = LoggerFactory.getLogger(HazelcastIdGenerationService::class.java)
        private const val PRODUCER_RETRY_MILLIS = 1_000L
        private val CONSUMER_WAIT_NANOS = TimeUnit.SECONDS.toNanos(5)
    }

    /*
     * Each range owns a portion of the keyspace.
     */
    private val hazelcastInstance = clients.getClient(HazelcastClient.IDS.name)
    private val scrolls = HazelcastMap.ID_GENERATION.getMap(hazelcastInstance)
    private val idsQueue = HazelcastQueue.ID_GENERATION.getQueue(hazelcastInstance)
    private val localQueue = Queues.newArrayBlockingQueue<UUID>(NUM_PARTITIONS) as BlockingQueue<UUID>
    private val executor = Executors.newSingleThreadExecutor()
    // At most one stalled distributed call per service. A full slot fails fast instead of
    // spawning unbounded callers or waiting for Hazelcast's network invocation timeout.
    private val consumerExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        { task -> Thread(task, "chronicle-id-consumer").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    init {
        if (!random && scrolls.isEmpty) {
            //Initialize the ranges
            scrolls.putAll((0L until NUM_PARTITIONS).associateWith { Range(it shl 48) })
        }

        executor.execute {
            while (!executor.isShutdown) {
                try {
                    if (random) {
                        idsQueue.put(UUID.randomUUID())
                    } else {
                        // Acquire before entering finally: a failed lock must not unlock another owner's fence.
                        scrolls.lock(0L)
                        val ids = try {
                            scrolls.executeOnEntries(IdsGeneratingEntryProcessor(PARTITION_SCROLL_SIZE)) as Map<Long, List<UUID>>
                        } finally {
                            scrolls.unlock(0L)
                        }
                        ids.values.asSequence().flatten().forEach { idsQueue.put(it) }
                        logger.debug("Added $NUM_PARTITIONS ids to queue")
                    }
                } catch (failure: Exception) {
                    if (executor.isShutdown) return@execute
                    logger.warn("ID production failed; retrying", failure)
                    // An interrupted distributed call can be transient; shutdown is checked separately.
                    Thread.interrupted()
                    try {
                        Thread.sleep(PRODUCER_RETRY_MILLIS)
                    } catch (_: InterruptedException) {
                        if (executor.isShutdown) return@execute
                    }
                }
            }
        }
    }

    @PreDestroy
    public fun shutdown() {
        executor.shutdownNow()
        consumerExecutor.shutdownNow()
    }

    /**
     * Returns an id to the local id for later use.
     * @param id to return to the pool
     */
    public fun returnId(id: UUID) {
        localQueue.offer(id)
    }

    public fun returnIds(ids: Collection<UUID>) {
        ids.forEach(::returnId)
    }

    public fun getNextIds(count: Int): Set<UUID> {
        return generateSequence { getNextId() }.take(count).toSet()
    }

    private fun boundedDistributedPoll(deadline: Long): UUID? {
        val invocation = consumerExecutor.submit<UUID?> {
            idsQueue.poll((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
        }
        return try {
            invocation.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
        } finally {
            if (!invocation.isDone) {
                invocation.cancel(true)
                consumerExecutor.purge()
            }
        }
    }

    public open fun getNextId(): UUID {
        val deadline = System.nanoTime() + CONSUMER_WAIT_NANOS
        try {
            while (true) {
                val remaining = deadline - System.nanoTime()
                val id = if (remaining > 0L) {
                    localQueue.poll() ?: boundedDistributedPoll(deadline)
                } else null
                if (id == null) {
                    throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ID allocation is temporarily unavailable")
                }
                // Reserved IDs must be skipped within the same bounded allocation wait.
                if ((id.mostSignificantBits != 0L) || (id.leastSignificantBits <= 0L) || (id.leastSignificantBits >= IdConstants.RESERVED_IDS_BASE)) {
                    return id
                }
            }
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ID allocation was interrupted", failure)
        } catch (failure: ResponseStatusException) {
            throw failure
        } catch (failure: Exception) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ID allocation is temporarily unavailable", failure)
        }
    }
}
