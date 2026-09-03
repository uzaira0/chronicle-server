package com.openlattice.chronicle.services.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ExportCapacityAdmissionTest {

    @Test
    fun testRecentSuccessfulCapacityRemainsAvailableWhileRefreshProbeIsHung() {
        val calls = AtomicInteger()
        val refreshStarted = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val refreshRanOnDaemon = AtomicBoolean()
        val probe = BoundedExportCapacityProbe(
            readUsableBytes = {
                when (calls.incrementAndGet()) {
                    1 -> 10_000L
                    else -> {
                        refreshRanOnDaemon.set(Thread.currentThread().isDaemon)
                        refreshStarted.countDown()
                        releaseRefresh.await()
                        9_000L
                    }
                }
            },
            timeout = Duration.ofMillis(100),
            cacheTtl = Duration.ofMinutes(1),
        )

        try {
            assertEquals(10_000L, probe.usableBytes())
            probe.refreshAsync(force = true)
            assertTrue(refreshStarted.await(1, TimeUnit.SECONDS))

            val startedAt = System.nanoTime()
            assertEquals(10_000L, probe.usableBytes())
            val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

            assertTrue("recent cached capacity must return promptly", elapsed < Duration.ofMillis(500))
            repeat(20) { probe.refreshAsync(force = true) }
            assertEquals("one stuck probe must not create more workers", 2, calls.get())
            assertTrue("capacity probes must not keep the server alive", refreshRanOnDaemon.get())
        } finally {
            releaseRefresh.countDown()
            probe.close()
        }
    }

    @Test
    fun testFreshCapacityNeverReusesProbeStartedBeforeTheFence() {
        val calls = AtomicInteger()
        val oldProbeStarted = CountDownLatch(1)
        val releaseOldProbe = CountDownLatch(1)
        val probe = BoundedExportCapacityProbe(
            readUsableBytes = {
                if (calls.incrementAndGet() == 1) {
                    oldProbeStarted.countDown()
                    releaseOldProbe.await()
                    10_000L
                } else {
                    9_000L
                }
            },
            timeout = Duration.ofSeconds(1),
            cacheTtl = Duration.ofMinutes(1),
        )
        val caller = Executors.newSingleThreadExecutor()

        try {
            probe.refreshAsync(force = true)
            assertTrue(oldProbeStarted.await(1, TimeUnit.SECONDS))
            val fresh = caller.submit<ExportStorageCapacity> { probe.freshCapacity() }

            releaseOldProbe.countDown()
            assertEquals(9_000L, fresh.get(1, TimeUnit.SECONDS).usableBytes)
            assertEquals("fresh admission must sample after its lock fence", 2, calls.get())
        } finally {
            releaseOldProbe.countDown()
            caller.shutdownNow()
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS))
            probe.close()
        }
    }

    @Test
    fun testHungCapacityProbeFailsClosedWithinBoundAndDoesNotGrowWorkers() {
        val calls = AtomicInteger()
        val probeStarted = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        val probeRanOnDaemon = AtomicBoolean()
        val probe = BoundedExportCapacityProbe(
            readUsableBytes = {
                calls.incrementAndGet()
                probeRanOnDaemon.set(Thread.currentThread().isDaemon)
                probeStarted.countDown()
                releaseProbe.await()
                10_000L
            },
            timeout = Duration.ofMillis(100),
            cacheTtl = Duration.ofMinutes(1),
        )
        val callers = Executors.newFixedThreadPool(8)

        try {
            val startedAt = System.nanoTime()
            val first = assertThrows(ExportResourceLimitException::class.java) {
                probe.usableBytes()
            }
            val firstElapsed = Duration.ofNanos(System.nanoTime() - startedAt)
            assertEquals(ExportCapacityUnavailableException.MESSAGE, first.message)
            assertTrue(probeStarted.await(1, TimeUnit.SECONDS))
            assertTrue("capacity timeout must fail closed promptly", firstElapsed < Duration.ofSeconds(1))

            val failures = (1..8).map {
                callers.submit<Boolean> {
                    assertThrows(ExportResourceLimitException::class.java) {
                        probe.usableBytes()
                    }
                    true
                }
            }
            failures.forEach { assertTrue(it.get(1, TimeUnit.SECONDS)) }

            assertEquals("all callers must share the single stuck probe", 1, calls.get())
            assertTrue("the bounded probe worker must be a daemon", probeRanOnDaemon.get())
        } finally {
            releaseProbe.countDown()
            callers.shutdownNow()
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS))
            probe.close()
        }
    }

    @Test
    fun testHungManagedArtifactScanIsBoundedAndSharesOneDaemonWorker() {
        val managedScans = AtomicInteger()
        val usableReads = AtomicInteger()
        val scanStarted = CountDownLatch(1)
        val releaseScan = CountDownLatch(1)
        val scanRanOnDaemon = AtomicBoolean()
        val probe = BoundedExportCapacityProbe(
            readUsableBytes = {
                usableReads.incrementAndGet()
                10_000L
            },
            readManagedArtifactBytes = {
                managedScans.incrementAndGet()
                scanRanOnDaemon.set(Thread.currentThread().isDaemon)
                scanStarted.countDown()
                releaseScan.await()
                0L
            },
            timeout = Duration.ofMillis(100),
            cacheTtl = Duration.ofMinutes(1),
        )
        val callers = Executors.newFixedThreadPool(4)

        try {
            val failures = (1..4).map {
                callers.submit<Boolean> {
                    assertThrows(ExportResourceLimitException::class.java) {
                        probe.freshCapacity()
                    }
                    true
                }
            }
            assertTrue(scanStarted.await(1, TimeUnit.SECONDS))
            failures.forEach { assertTrue(it.get(1, TimeUnit.SECONDS)) }

            assertEquals("all callers must share one managed-artifact scan", 1, managedScans.get())
            assertEquals("usable space must not run before the managed scan completes", 0, usableReads.get())
            assertTrue("managed-artifact scans must not keep the server alive", scanRanOnDaemon.get())
        } finally {
            releaseScan.countDown()
            callers.shutdownNow()
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS))
            probe.close()
        }
    }

    @Test
    fun testExpiredCapacityCacheFailsClosedWhenReplacementProbeHangs() {
        val clock = AtomicLong()
        val calls = AtomicInteger()
        val replacementStarted = CountDownLatch(1)
        val releaseReplacement = CountDownLatch(1)
        val probe = BoundedExportCapacityProbe(
            readUsableBytes = {
                if (calls.incrementAndGet() == 1) {
                    10_000L
                } else {
                    replacementStarted.countDown()
                    releaseReplacement.await()
                    9_000L
                }
            },
            timeout = Duration.ofMillis(100),
            cacheTtl = Duration.ofNanos(10),
            nanoTime = clock::get,
        )

        try {
            assertEquals(10_000L, probe.usableBytes())
            clock.set(11L)

            assertThrows(ExportResourceLimitException::class.java) {
                probe.usableBytes()
            }
            assertTrue(replacementStarted.await(1, TimeUnit.SECONDS))
            assertThrows(ExportResourceLimitException::class.java) {
                probe.usableBytes()
            }
            assertEquals("the stuck replacement must remain the only probe", 2, calls.get())
        } finally {
            releaseReplacement.countDown()
            probe.close()
        }
    }

    @Test
    fun testInterruptedCapacityWaitPreservesShutdownSignal() {
        val probeStarted = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        val probe = BoundedExportCapacityProbe(
            readUsableBytes = {
                probeStarted.countDown()
                releaseProbe.await()
                10_000L
            },
            timeout = Duration.ofSeconds(1),
            cacheTtl = Duration.ofMinutes(1),
        )

        try {
            probe.refreshAsync()
            assertTrue(probeStarted.await(1, TimeUnit.SECONDS))
            Thread.currentThread().interrupt()

            assertThrows(InterruptedException::class.java) {
                probe.usableBytes()
            }
            assertTrue("the caller's shutdown signal must remain set", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            releaseProbe.countDown()
            probe.close()
        }
    }

    @Test
    fun testHungCapacityReadDoesNotHoldGlobalReservationLock() {
        val reads = AtomicInteger()
        val firstReadStarted = CountDownLatch(1)
        val releaseFirstRead = CountDownLatch(1)
        val gate = ExportCapacityReservationGate(
            maximumManagedArtifactBytes = 10_000L,
            minimumFreeBytes = 100L,
            storageCapacity = {
                if (reads.incrementAndGet() == 1) {
                    firstReadStarted.countDown()
                    releaseFirstRead.await()
                    throw ExportCapacityUnavailableException()
                }
                ExportStorageCapacity(usableBytes = 10_000L, managedArtifactBytesAtSample = 0L)
            },
        )
        val callers = Executors.newFixedThreadPool(2)

        try {
            val blocked = callers.submit<Boolean> {
                assertThrows(ExportResourceLimitException::class.java) {
                    gate.withReservation(1_000L) { error("blocked probe must not enter the action") }
                }
                true
            }
            assertTrue(firstReadStarted.await(1, TimeUnit.SECONDS))

            val unrelated = callers.submit<Boolean> {
                gate.withReservation(1_000L) { true }
            }
            assertTrue(
                "a capacity probe blocked outside the gate must not starve another reservation",
                unrelated.get(1, TimeUnit.SECONDS),
            )

            releaseFirstRead.countDown()
            assertTrue(blocked.get(1, TimeUnit.SECONDS))
        } finally {
            releaseFirstRead.countDown()
            callers.shutdownNow()
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun testKnownCapacityPreservesAggregateAndFreeSpaceAdmissionRules() {
        val rejectionReasons = mutableListOf<String>()
        val aggregateGate = ExportCapacityReservationGate(
            maximumManagedArtifactBytes = 100L,
            minimumFreeBytes = 10L,
            storageCapacity = {
                ExportStorageCapacity(usableBytes = 1_000L, managedArtifactBytesAtSample = 50L)
            },
            onRejection = rejectionReasons::add,
        )
        val aggregateFailure = assertThrows(ExportResourceLimitException::class.java) {
            aggregateGate.withReservation(51L) { error("over-limit action must not run") }
        }
        assertTrue(aggregateFailure.message.orEmpty().contains("managed artifact limit"))
        assertEquals(listOf("aggregate_limit"), rejectionReasons)

        rejectionReasons.clear()
        val freeSpaceGate = ExportCapacityReservationGate(
            maximumManagedArtifactBytes = 10_000L,
            minimumFreeBytes = 10L,
            storageCapacity = {
                ExportStorageCapacity(usableBytes = 0L, managedArtifactBytesAtSample = 0L)
            },
            onRejection = rejectionReasons::add,
        )
        val freeSpaceFailure = assertThrows(ExportResourceLimitException::class.java) {
            freeSpaceGate.withReservation(1L) { error("below-floor action must not run") }
        }
        assertTrue(freeSpaceFailure.message.orEmpty().contains("free-space reserve"))
        assertEquals(listOf("free_space_floor"), rejectionReasons)
    }

    @Test
    fun testPublicationBetweenSampleAndLockForcesFreshSnapshotBeforeAdmission() {
        val capacityReads = AtomicInteger()
        val firstActionStarted = CountDownLatch(1)
        val releaseFirstAction = CountDownLatch(1)
        val staleSnapshotCaptured = CountDownLatch(1)
        val returnStaleSnapshot = CountDownLatch(1)
        val gate = ExportCapacityReservationGate(
            maximumManagedArtifactBytes = 10_000L,
            minimumFreeBytes = 1_000L,
            storageCapacity = {
                when (capacityReads.incrementAndGet()) {
                    1 -> ExportStorageCapacity(usableBytes = 1_600L, managedArtifactBytesAtSample = 0L)
                    2 -> {
                        staleSnapshotCaptured.countDown()
                        returnStaleSnapshot.await()
                        ExportStorageCapacity(usableBytes = 1_600L, managedArtifactBytesAtSample = 0L)
                    }
                    else -> ExportStorageCapacity(usableBytes = 1_100L, managedArtifactBytesAtSample = 500L)
                }
            },
        )
        val callers = Executors.newFixedThreadPool(2)

        try {
            val publisher = callers.submit<Boolean> {
                gate.withReservation(500L) {
                    firstActionStarted.countDown()
                    releaseFirstAction.await()
                    true
                }
            }
            assertTrue(firstActionStarted.await(1, TimeUnit.SECONDS))

            val contender = callers.submit<Boolean> {
                assertThrows(ExportResourceLimitException::class.java) {
                    gate.withReservation(500L) { error("stale pre-publication snapshot must not admit") }
                }
                true
            }
            assertTrue(staleSnapshotCaptured.await(1, TimeUnit.SECONDS))

            releaseFirstAction.countDown()
            assertTrue(publisher.get(1, TimeUnit.SECONDS))
            returnStaleSnapshot.countDown()
            assertTrue(contender.get(1, TimeUnit.SECONDS))

            assertEquals("epoch change must force one fresh retry", 3, capacityReads.get())
        } finally {
            releaseFirstAction.countDown()
            returnStaleSnapshot.countDown()
            callers.shutdownNow()
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun testZeroUsableBytesIsKnownCapacityAndNotAProbeFailure() {
        val probe = BoundedExportCapacityProbe(
            readUsableBytes = { 0L },
            timeout = Duration.ofMillis(100),
            cacheTtl = Duration.ofMinutes(1),
        )

        try {
            assertEquals(0L, probe.usableBytes())
        } finally {
            probe.close()
        }
    }
}
