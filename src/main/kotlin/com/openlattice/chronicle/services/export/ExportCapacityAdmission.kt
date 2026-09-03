package com.openlattice.chronicle.services.export

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Reads filesystem capacity on one bounded daemon worker. Native filesystem probes are not
 * reliably interruptible on every supported operating system, so callers share at most one
 * in-flight probe and never create replacement threads while it remains stuck.
 */
internal class BoundedExportCapacityProbe(
    private val readUsableBytes: () -> Long,
    private val readManagedArtifactBytes: () -> Long = { 0L },
    private val timeout: Duration,
    private val cacheTtl: Duration,
    threadName: String = "chronicle-export-capacity-probe",
    private val nanoTime: () -> Long = System::nanoTime,
    private val onSuccess: (ExportStorageCapacity) -> Unit = {},
) : AutoCloseable {
    init {
        require(!timeout.isZero && !timeout.isNegative) { "Capacity probe timeout must be positive" }
        require(!cacheTtl.isZero && !cacheTtl.isNegative) { "Capacity cache TTL must be positive" }
    }

    private data class CapacitySnapshot(
        val capacity: ExportStorageCapacity,
        val capturedAtNanos: Long,
        val sequence: Long,
    )

    private data class ActiveProbe(
        val sequence: Long,
        val result: CompletableFuture<ExportStorageCapacity>,
    )

    private val stateLock = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    }

    @Volatile
    private var cachedSnapshot: CapacitySnapshot? = null

    @Volatile
    private var inFlight: ActiveProbe? = null
    private var nextSequence = 0L

    fun capacity(): ExportStorageCapacity {
        recentCapacity()?.capacity?.let { return it }
        return try {
            awaitProbe(currentOrStartProbe(force = false).result)
        } catch (_: RejectedExecutionException) {
            throw ExportCapacityUnavailableException()
        }
    }

    /**
     * Returns a sample whose probe started after this call's admission fence. If an older probe is
     * already running, wait for it only within the same timeout budget and then start one successor.
     */
    fun freshCapacity(): ExportStorageCapacity {
        val targetSequence = synchronized(stateLock) {
            val active = inFlight?.takeUnless { it.result.isDone }
            (active?.sequence ?: nextSequence) + 1L
        }
        val startedAtNanos = System.nanoTime()
        val timeoutNanos = timeout.toNanos()
        while (true) {
            val probe = try {
                currentOrStartProbe(force = true)
            } catch (_: RejectedExecutionException) {
                capacityUnavailable()
            }
            val elapsedNanos = (System.nanoTime() - startedAtNanos).coerceAtLeast(0L)
            val remainingNanos = timeoutNanos - elapsedNanos
            if (remainingNanos <= 0L) capacityUnavailable()
            try {
                val capacity = probe.result.get(remainingNanos, TimeUnit.NANOSECONDS)
                if (probe.sequence >= targetSequence) return capacity
            } catch (_: TimeoutException) {
                capacityUnavailable()
            } catch (_: ExecutionException) {
                if (probe.sequence >= targetSequence) capacityUnavailable()
            } catch (_: RejectedExecutionException) {
                capacityUnavailable()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
        }
    }

    private fun awaitProbe(probe: CompletableFuture<ExportStorageCapacity>): ExportStorageCapacity =
        try {
            probe.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            throw ExportCapacityUnavailableException()
        } catch (_: ExecutionException) {
            throw ExportCapacityUnavailableException()
        } catch (_: RejectedExecutionException) {
            throw ExportCapacityUnavailableException()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        }

    fun usableBytes(): Long = capacity().usableBytes

    /** Starts a refresh without making the calling/request thread wait for native I/O. */
    fun refreshAsync(force: Boolean = false) {
        if (!force && recentCapacity() != null) return
        try {
            currentOrStartProbe(force)
        } catch (_: RejectedExecutionException) {
            // A shutting-down process does not need a replacement metrics refresh.
        }
    }

    fun lastSuccessfulCapacity(): ExportStorageCapacity? = cachedSnapshot?.capacity

    private fun recentCapacity(): CapacitySnapshot? {
        val snapshot = cachedSnapshot ?: return null
        val ageNanos = (nanoTime() - snapshot.capturedAtNanos).coerceAtLeast(0L)
        return snapshot.takeIf { ageNanos <= cacheTtl.toNanos() }
    }

    private fun capacityUnavailable(): Nothing = throw ExportCapacityUnavailableException()

    // Every supplier failure must complete the shared future; otherwise callers wait for a
    // timeout and the in-flight probe remains stuck. This executor boundary intentionally catches
    // Throwable so even an Error from a native/filesystem supplier is published to every waiter.
    @Suppress("TooGenericExceptionCaught")
    private fun currentOrStartProbe(force: Boolean): ActiveProbe =
        synchronized(stateLock) {
            if (!force) {
                recentCapacity()?.let { snapshot ->
                    return@synchronized ActiveProbe(
                        snapshot.sequence,
                        CompletableFuture.completedFuture(snapshot.capacity),
                    )
                }
            }
            inFlight?.takeUnless { it.result.isDone }?.let { return@synchronized it }

            nextSequence += 1L
            val result = CompletableFuture<ExportStorageCapacity>()
            val activeProbe = ActiveProbe(nextSequence, result)
            inFlight = activeProbe
            try {
                executor.execute {
                    try {
                        // Managed bytes are sampled first. A concurrent publication between these
                        // reads can only make admission more conservative, never less.
                        val managedArtifactBytes = readManagedArtifactBytes()
                        val usableBytes = readUsableBytes()
                        check(usableBytes >= 0L && managedArtifactBytes >= 0L) {
                            "Filesystem reported invalid export capacity"
                        }
                        val capacity = ExportStorageCapacity(usableBytes, managedArtifactBytes)
                        cachedSnapshot = CapacitySnapshot(capacity, nanoTime(), activeProbe.sequence)
                        runCatching { onSuccess(capacity) }
                        result.complete(capacity)
                    } catch (failure: Throwable) {
                        result.completeExceptionally(failure)
                    } finally {
                        synchronized(stateLock) {
                            if (inFlight === activeProbe) inFlight = null
                        }
                    }
                }
            } catch (failure: RejectedExecutionException) {
                inFlight = null
                result.completeExceptionally(failure)
                throw failure
            }
            activeProbe
        }

    override fun close() {
        executor.shutdownNow()
    }
}

internal data class ExportStorageCapacity(
    val usableBytes: Long,
    val managedArtifactBytesAtSample: Long,
)

/**
 * Serializes only the in-memory reservation calculation. The filesystem capacity supplier is
 * deliberately invoked before this lock so a native probe cannot starve unrelated admissions.
 */
internal class ExportCapacityReservationGate(
    private val maximumManagedArtifactBytes: Long,
    private val minimumFreeBytes: Long,
    private val storageCapacity: () -> ExportStorageCapacity,
    private val onAdmission: (managedBytes: Long, usableBytes: Long) -> Unit = { _, _ -> },
    private val onRejection: (reason: String) -> Unit = {},
) {
    private val reservationLock = Any()
    private var reservedCapacityBytes = 0L
    private var reservationEpoch = 0L

    fun <T> withReservation(requestedBytes: Long, action: () -> T): T {
        require(requestedBytes > 0L) { "Requested export capacity must be positive" }

        repeat(MAX_SNAPSHOT_RETRIES) {
            val observedEpoch = synchronized(reservationLock) { reservationEpoch }
            // This may await one bounded daemon probe. It must never run under reservationLock.
            val capacity = storageCapacity()
            var admitted = false
            synchronized(reservationLock) {
                if (reservationEpoch == observedEpoch) {
                    val projectedManagedBytes = saturatingAdd(
                        saturatingAdd(capacity.managedArtifactBytesAtSample, reservedCapacityBytes),
                        requestedBytes,
                    )
                    if (projectedManagedBytes > maximumManagedArtifactBytes) {
                        onRejection("aggregate_limit")
                        throw ExportResourceLimitException(
                            "Export storage exceeds the managed artifact limit ($maximumManagedArtifactBytes bytes)",
                        )
                    }

                    val requiredUsableBytes = saturatingAdd(
                        saturatingAdd(reservedCapacityBytes, requestedBytes),
                        minimumFreeBytes,
                    )
                    if (capacity.usableBytes < requiredUsableBytes) {
                        onRejection("free_space_floor")
                        throw ExportResourceLimitException(
                            "Export storage does not have the required free-space reserve ($minimumFreeBytes bytes)",
                        )
                    }
                    reservedCapacityBytes = saturatingAdd(reservedCapacityBytes, requestedBytes)
                    reservationEpoch += 1L
                    admitted = true
                }
            }
            if (!admitted) return@repeat

            try {
                onAdmission(capacity.managedArtifactBytesAtSample, capacity.usableBytes)
                return action()
            } finally {
                synchronized(reservationLock) {
                    reservedCapacityBytes = (reservedCapacityBytes - requestedBytes).coerceAtLeast(0L)
                    reservationEpoch += 1L
                }
            }
        }
        throw ExportCapacityUnavailableException()
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private companion object {
        const val MAX_SNAPSHOT_RETRIES = 3
    }
}

internal class ExportCapacityUnavailableException : ExportResourceLimitException(MESSAGE) {
    companion object {
        const val MESSAGE = "Export storage capacity is temporarily unavailable"
    }
}
