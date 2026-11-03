package com.openlattice.chronicle.services

import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.services.jobs.JobService
import com.openlattice.chronicle.services.studies.tasks.StudyComplianceHazelcastTask
import com.openlattice.chronicle.services.studies.tasks.StudyComplianceHazelcastTaskDependencies
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.tasks.MoveAndroidSensorDataToStorageTask
import com.openlattice.chronicle.storage.tasks.MoveToEventStorageTaskDependencies
import com.openlattice.chronicle.study.StudyComplianceManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.sql.SQLTransientConnectionException

class BackgroundTaskFailureTest {
    @Test
    fun sensorMoverFailureReachesTheBackgroundFailureCounterAndKeepsItsExistingFailureCounter() {
        val storage = mock<StorageResolver>()
        whenever(storage.getPlatformStorage()).thenAnswer { throw SQLTransientConnectionException("database unavailable") }
        val dependencies = MoveToEventStorageTaskDependencies(storage, mock())
        val task = object : MoveAndroidSensorDataToStorageTask() {
            override fun getDependency() = dependencies
        }
        val failures = ChronicleMetrics.backgroundTaskFailuresTotal.labels("sensor_mover")
        val sensorFailures = ChronicleMetrics.sensorMaterializationRunsTotal.labels("failure")
        val before = failures.get()
        val sensorBefore = sensorFailures.get()

        task.run()

        assertEquals(before + 1, failures.get(), 0.0)
        assertEquals(sensorBefore + 1, sensorFailures.get(), 0.0)
    }

    @Test
    fun leaseReconciliationFailureReachesTheBackgroundFailureCounter() {
        val storage = mock<StorageResolver>()
        whenever(storage.getPlatformStorage()).thenAnswer { throw SQLTransientConnectionException("database unavailable") }
        val jobs = JobService(mock(), storage, mock())
        jobs.shutdown()
        val failures = ChronicleMetrics.backgroundTaskFailuresTotal.labels("job_reconcile")
        val before = failures.get()

        jobs.tryAndAcquireTaskForExecutor()

        assertEquals(before + 1, failures.get(), 0.0)
    }

    @Test
    fun complianceFailureIsCountedBeforeTheHazelcastBoundaryHandlesIt() {
        val compliance = mock<StudyComplianceManager>()
        whenever(compliance.getAllNonCompliantStudies()).thenAnswer { throw SQLTransientConnectionException("database unavailable") }
        val dependencies = StudyComplianceHazelcastTaskDependencies(compliance, mock(), mock(), mock())
        val task = object : StudyComplianceHazelcastTask() {
            override fun getDependency() = dependencies
        }
        val failures = ChronicleMetrics.backgroundTaskFailuresTotal.labels("compliance")
        val before = failures.get()

        task.run()

        assertEquals(before + 1, failures.get(), 0.0)
    }
}
