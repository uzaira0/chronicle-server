package com.openlattice.chronicle.storage

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.springframework.web.server.ResponseStatusException
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

    @Test
    fun busyExportRejectsDeletionWithoutQueuingAheadOfUploads() {
        val pg = ChronicleContractTestSchema.sharedPostgres
        val study = UUID.randomUUID()
        pg.createConnection("").use { export ->
            export.autoCommit = false
            DeletionStudyFence.exportShared(export, study)
            pg.createConnection("").use { deletion ->
                deletion.autoCommit = false
                deletion.createStatement().use { it.execute("SET LOCAL lock_timeout = '1s'") }
                val rejected = assertThrows(ResponseStatusException::class.java) { DeletionStudyFence.exclusive(deletion, study) }
                assertEquals(409, rejected.statusCode.value())
                deletion.rollback() // The orchestrator rolls back a refused quarantine before returning 409.
                pg.createConnection("").use { upload ->
                    upload.autoCommit = false
                    DeletionStudyFence.shared(upload, study)
                    upload.rollback()
                }
                export.rollback()
                DeletionStudyFence.exclusive(deletion, study)
                deletion.rollback()
            }
        }
    }
}
