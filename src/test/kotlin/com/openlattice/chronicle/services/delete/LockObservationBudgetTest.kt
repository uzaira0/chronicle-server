package com.openlattice.chronicle.services.delete

import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LockObservationBudgetTest : ServerSweepTestFixture() {
    @Test
    fun aScheduledWriterCanReachTheDatabaseAfterTheOldObservationDeadline() {
        val study = UUID.randomUUID()
        seed(study, "delayed-writer")
        val name = "delayed-writer-${UUID.randomUUID()}"
        val executor = Executors.newSingleThreadScheduledExecutor()
        try {
            postgres.createConnection("").use { blocker ->
                blocker.autoCommit = false
                lockStudyRow(blocker, study)
                val writer = executor.schedule({ readAfterLock(name, study) }, 6, TimeUnit.SECONDS)
                try {
                    awaitBlocked(name)
                    blocker.rollback()
                    writer.get(5, TimeUnit.SECONDS)
                } finally {
                    blocker.rollback()
                }
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun readAfterLock(name: String, study: UUID) = pool(name).use { pool ->
        pool.connection.use { connection -> lockStudyRow(connection, study) }
    }

    private fun lockStudyRow(connection: Connection, study: UUID) {
        connection.prepareStatement("SELECT study_id FROM studies WHERE study_id = ? FOR UPDATE").use {
            it.setObject(1, study)
            it.executeQuery().close()
        }
    }
}
