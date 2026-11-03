package com.openlattice.chronicle.storage

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class DeletionStudyFenceTest {
    @Test
    fun quarantineWaitsForWritersAndCompetingDeletions() {
        val pg = ChronicleContractTestSchema.sharedPostgres
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            for (exclusive in listOf(false, true)) {
                val study = UUID.randomUUID()
                val attempting = java.util.concurrent.CountDownLatch(1)
                pg.createConnection("").use { owner ->
                    owner.autoCommit = false
                    if (exclusive) DeletionStudyFence.exclusive(owner, study) else DeletionStudyFence.shared(owner, study)
                    val quarantine = executor.submit {
                        pg.createConnection("").use { connection ->
                            connection.autoCommit = false
                            attempting.countDown()
                            DeletionStudyFence.exclusive(connection, study)
                            connection.rollback()
                        }
                    }
                    org.junit.Assert.assertTrue(attempting.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    assertThrows(java.util.concurrent.TimeoutException::class.java) {
                        quarantine.get(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                    }
                    owner.rollback()
                    quarantine.get(5, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        } finally {
            executor.shutdownNow()
            org.junit.Assert.assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
        }
    }
}
