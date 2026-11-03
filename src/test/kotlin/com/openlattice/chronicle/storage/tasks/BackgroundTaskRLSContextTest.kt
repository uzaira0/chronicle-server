package com.openlattice.chronicle.storage.tasks

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.observability.ChronicleMetrics
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.rls.RLSConnectionContext
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class BackgroundTaskRLSContextTest {
    private class ProbeComplete : RuntimeException()

    @After
    fun tearDown() {
        RLSRequestContext.clear()
    }

    @Test
    fun `system context restores an enclosing request context`() {
        val requestContext = RLSConnectionContext(
            principalId = "request-user",
            authorizedStudyIds = setOf(UUID.randomUUID()),
            isAdmin = false,
        )
        RLSRequestContext.set(requestContext)

        RLSRequestContext.withSystemContext {
            val current = requireNotNull(RLSRequestContext.current())
            assertEquals("chronicle-background", current.principalId)
            assertTrue(current.isAdmin)
            assertTrue(current.authorizedStudyIds.isEmpty())
        }

        assertEquals(requestContext, RLSRequestContext.current())
    }

    @Test
    fun `system context clears after an exception`() {
        assertThrows(ProbeComplete::class.java) {
            RLSRequestContext.withSystemContext {
                throw ProbeComplete()
            }
        }

        assertNull(RLSRequestContext.current())
    }

    @Test
    fun `deletion worker context restores an enclosing request context`() {
        val requestContext = RLSConnectionContext(
            principalId = "request-user",
            authorizedStudyIds = setOf(UUID.randomUUID()),
            isAdmin = false,
        )
        RLSRequestContext.set(requestContext)

        RLSRequestContext.withDeletionWorkerContext {
            assertDeletionWorkerContext(RLSRequestContext.current())
        }

        assertEquals(requestContext, RLSRequestContext.current())
    }

    @Test
    fun `deletion worker context clears after an exception`() {
        assertThrows(ProbeComplete::class.java) {
            RLSRequestContext.withDeletionWorkerContext {
                throw ProbeComplete()
            }
        }

        assertNull(RLSRequestContext.current())
    }

    @Test
    fun `deletion orchestrator establishes worker context before borrowing storage`() {
        val seen = AtomicReference<RLSConnectionContext?>()
        val storageResolver = mock<StorageResolver>()
        whenever(storageResolver.getPlatformStorage()).thenAnswer {
            seen.set(RLSRequestContext.current())
            throw ProbeComplete()
        }
        val orchestrator = DataDeletionOrchestrator(storageResolver, mock<AuditingManager>())

        assertThrows(ProbeComplete::class.java) {
            orchestrator.processDueOperations(limit = 1)
        }

        assertDeletionWorkerContext(seen.get())
        assertNull(RLSRequestContext.current())
    }

    @Test
    fun `a failed mover run is counted for the background-task alert`() {
        for ((task, mover) in listOf(
            ChronicleMetrics.BackgroundTask.USAGE_MOVER to object : MoveToEventStorageTask() {
                override fun getDependency() = probingDependencies(AtomicReference())
            },
        )) {
            val failures = ChronicleMetrics.backgroundTaskFailuresTotal.labels(task.name.lowercase())
            val before = failures.get()

            mover.runTask()

            assertEquals(before + 1, failures.get(), 0.0)
        }

        // The shipped Prometheus scrape keeps the counter and the shipped alert sums it by task.
        val sample = ChronicleMetrics.backgroundTaskFailuresTotal.collect().flatMap { it.samples }
            .single { it.labelValues == listOf("usage_mover") && it.name.endsWith("_total") }
        val mapper = YAMLMapper()
        // The scrape config ships as a YAML heredoc; parsing it avoids executing the privileged renderer.
        val scrape = mapper.readTree(File("../selfhost/monitoring/render-config.sh").readLines()
            .dropWhile { it != "global:" }.takeWhile { it != "EOF" }.joinToString("\n"))
        val backend = scrape["scrape_configs"].single { it["job_name"].asText() == "chronicle-backend" }
        val keeps = backend["metric_relabel_configs"].filter { it["action"].asText() == "keep" }
        assertTrue("backend scrape has no metric allowlist", keeps.isNotEmpty())
        keeps.forEach { rule ->
            assertEquals(listOf("__name__"), rule["source_labels"].map { it.asText() })
            assertTrue("scrape drops ${sample.name}", Regex(rule["regex"].asText()).matches(sample.name))
        }
        val rules = mapper.readTree(File("../selfhost/monitoring/grafana-alerting/rules.yml"))
        assertTrue(
            "no shipped alert sums ${sample.name} by task",
            rules["groups"].flatMap { it["rules"] }.flatMap { it["data"] }
                .any { it["model"]["expr"]?.asText() == "sum by (task) (increase(${sample.name}[1h]))" },
        )
    }

    @Test
    fun `android sensor mover establishes system context inside its executor thread`() {
        val seen = AtomicReference<RLSConnectionContext?>()
        val dependencies = probingDependencies(seen)
        val mover = object : MoveAndroidSensorDataToStorageTask() {
            override fun getDependency(): MoveToEventStorageTaskDependencies = dependencies
        }

        assertThrows(IllegalStateException::class.java) {
            mover.runTask()
        }

        assertSystemContext(seen.get())
    }

    private fun probingDependencies(
        seen: AtomicReference<RLSConnectionContext?>,
    ): MoveToEventStorageTaskDependencies {
        val storageResolver = mock<StorageResolver>()
        whenever(storageResolver.getPlatformStorage()).thenAnswer {
            seen.set(RLSRequestContext.current())
            throw ProbeComplete()
        }
        return MoveToEventStorageTaskDependencies(storageResolver, mock<StudyManager>())
    }

    private fun assertSystemContext(context: RLSConnectionContext?) {
        val actual = requireNotNull(context)
        assertEquals("chronicle-background", actual.principalId)
        assertTrue(actual.isAdmin)
        assertTrue(actual.authorizedStudyIds.isEmpty())
        assertEquals(false, actual.isDeletionWorker)
    }

    private fun assertDeletionWorkerContext(context: RLSConnectionContext?) {
        val actual = requireNotNull(context)
        assertEquals("chronicle-deletion-worker", actual.principalId)
        assertTrue(actual.isAdmin)
        assertTrue(actual.authorizedStudyIds.isEmpty())
        assertTrue(actual.isDeletionWorker)
    }
}
