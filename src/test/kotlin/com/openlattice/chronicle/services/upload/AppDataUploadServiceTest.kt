package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.storage.ResolvedStorage

import com.openlattice.chronicle.ScopedLogCapture
import com.openlattice.chronicle.observability.ChronicleMetrics
import org.apache.logging.log4j.Level

import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.PreparedStatement
import com.geekbeast.configuration.postgres.PostgresFlavor
import java.sql.ResultSet

import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Semaphore

/**
 * Pins the input-validation guard that protects the Android usage upload path —
 * the batch-size limit — at the service boundary, with mocked storage so no
 * Testcontainers DB is needed. Enrollment/datasource authorization is owned by
 * StudyController's shared mobile-upload gate. The deeper
 * persistence behavior (upload-buffer insert, move-to-storage, activity_class) is
 * exercised by the Testcontainers `DataUploadE2ETest`.
 */
class AppDataUploadServiceTest {

    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private val studyManager = Mockito.mock(StudyManager::class.java)

    private lateinit var service: AppDataUploadService

    @Before
    fun setUp() {
        service = AppDataUploadService(storageResolver, studyManager)
    }

    private fun usageEvent(): ChronicleUsageEvent = ChronicleUsageEvent(
        studyId = UUID.randomUUID(),
        participantId = "p1",
        appPackageName = "com.example.app",
        interactionType = "Move to Foreground",
        timestamp = OffsetDateTime.now(),
        timezone = "UTC",
        user = "",
        applicationLabel = "Example",
    )

    @Test
    fun malformedValuesAreRejectedBeforeAnyStorageOrStatsWork() {
        val valid = usageEvent()
        val invalid = listOf(
            valid.copy(timezone = "UTC\u0000"),
            valid.copy(timestamp = OffsetDateTime.MAX),
        )
        for (event in invalid) {
            assertThrows(UploadRowRejectedException::class.java) {
                service.uploadAndroidUsageEvents(UUID.randomUUID(), "p1", UUID.randomUUID(), listOf(event))
            }
        }
        Mockito.verifyNoInteractions(storageResolver, studyManager)
    }

    @Test
    fun uploadAcknowledgesOnlyTheEventsItActuallyStored() {
        // Events whose package name contains '[' are dropped. The receipt must name only the stored
        // events, and the drop must reach the error log and the upload-error counter.
        val studyId = UUID.randomUUID()
        val hds = Mockito.mock(HikariDataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        val statement = Mockito.mock(PreparedStatement::class.java)
        val permitted = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(storageResolver.resolveAndGetFlavor(studyId))
            .thenReturn(ResolvedStorage(PostgresFlavor.VANILLA, hds))
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(hds)
        Mockito.`when`(hds.connection).thenReturn(connection)
        Mockito.`when`(connection.prepareStatement(Mockito.anyString())).thenReturn(statement)
        Mockito.`when`(statement.executeUpdate()).thenReturn(1)
        Mockito.`when`(statement.executeQuery()).thenReturn(permitted)
        stubOpenCollection(connection)
        Mockito.`when`(permitted.getInt(1)).thenReturn(1)

        val kept = usageEvent().copy(clientEventId = UUID.randomUUID())
        val malformedCounter = ChronicleMetrics.uploadErrors.labels("android_usage", "malformed_package_name")
        val before = malformedCounter.get()
        val stored = ScopedLogCapture(AppDataUploadService::class.java, Level.ERROR).use { errors ->
            service.uploadAndroidUsageEvents(
                studyId,
                "p1",
                UUID.randomUUID(),
                listOf(kept, kept.copy(appPackageName = "com.example.app[bad]", clientEventId = UUID.randomUUID())),
            ).also { assertTrue(errors.messages.any { "discarded 1 usage event" in it }) }
        }

        assertEquals(setOf(kept.clientEventId), stored)
        assertEquals(1.0, malformedCounter.get() - before, 0.0)
    }

    @Test
    fun eventsTooLargeForTheIndexKeysAreDiscardedNotBuffered() {
        // An index key over the btree limit fails every drain of the batch that holds it and
        // stalls the mover for every study. Such events never reach the upload buffer.
        val studyId = UUID.randomUUID()
        val hds = Mockito.mock(HikariDataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        val statement = Mockito.mock(PreparedStatement::class.java)
        val permitted = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(storageResolver.resolveAndGetFlavor(studyId)).thenReturn(ResolvedStorage(PostgresFlavor.VANILLA, hds))
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(hds)
        Mockito.`when`(hds.connection).thenReturn(connection)
        Mockito.`when`(connection.prepareStatement(Mockito.anyString())).thenReturn(statement)
        Mockito.`when`(statement.executeUpdate()).thenReturn(1)
        Mockito.`when`(statement.executeQuery()).thenReturn(permitted)
        stubOpenCollection(connection)
        Mockito.`when`(permitted.getInt(1)).thenReturn(1)

        val kept = usageEvent().copy(clientEventId = UUID.randomUUID())
        val longLabel = kept.copy(applicationLabel = "x".repeat(1024), clientEventId = UUID.randomUUID())
        val btreeMaxItemBytes = 2704
        val oversized = listOf(
            kept.copy(applicationLabel = "x".repeat(btreeMaxItemBytes), clientEventId = UUID.randomUUID()),
            kept.copy(activityClass = "é".repeat(btreeMaxItemBytes / 2), clientEventId = UUID.randomUUID()),
            // Each field fits on its own; together they overflow the one identity key (chronicle_usage_events_identity).
            longLabel.copy(activityClass = "c".repeat(1100), clientEventId = UUID.randomUUID()),
        )
        val counter = ChronicleMetrics.uploadErrors.labels("android_usage", "index_key_too_large")
        val before = counter.get()
        val stored = service.uploadAndroidUsageEvents(studyId, "p1", UUID.randomUUID(), listOf(kept, longLabel) + oversized)

        assertEquals(setOf(kept.clientEventId, longLabel.clientEventId), stored)
        assertEquals(3.0, counter.get() - before, 0.0)
    }

    @Test
    fun uploadRejectsBatchLargerThanTheRowCap() {
        // The service rejects an oversized batch before touching storage — the guard
        // is a hard input bound, not a best-effort check.
        val tooLarge = (0..MAX_UPLOAD_ROWS).map { usageEvent() }
        try {
            service.uploadAndroidUsageEvents(UUID.randomUUID(), "p1", UUID.randomUUID(), tooLarge)
            fail("Expected upload of ${tooLarge.size} events to be rejected (max $MAX_UPLOAD_ROWS)")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Rejection message should mention the batch is too large, was: ${e.message}",
                e.message?.contains("too large") == true,
            )
        }
        // Storage must never be touched for an oversized batch.
        Mockito.verifyNoInteractions(storageResolver)
    }

    @Test
    fun uploadAcceptsABatchAtTheRowCap() {
        val studyId = UUID.randomUUID()
        val unavailable = IllegalStateException("synthetic storage failure")
        Mockito.`when`(storageResolver.resolveAndGetFlavor(studyId)).thenThrow(unavailable)
        val atLimit = (1..MAX_UPLOAD_ROWS).map { usageEvent() }
        val failure = assertThrows(IllegalStateException::class.java) {
            service.uploadAndroidUsageEvents(studyId, "p1", UUID.randomUUID(), atLimit)
        }
        assertSame(unavailable, failure)
        Mockito.verify(storageResolver).resolveAndGetFlavor(studyId)
    }

    @Test
    fun uploadPropagatesUnresolvableStorageFailure() {
        // storageResolver is an unstubbed mock, so resolving the event store fails.
        // The service must propagate that failure rather than acknowledge the batch.
        val studyId = UUID.randomUUID()
        assertThrows(Exception::class.java) {
            service.uploadAndroidUsageEvents(studyId, "p1", UUID.randomUUID(), listOf(usageEvent()))
        }
    }

    /**
     * The admin "move to storage" drain fails loudly when platform storage is down, and every failed drain
     * returns its permit: more failures than there are permits still reach storage instead of silently skipping.
     */
    @Test
    fun drainFailureReachesTheCallerAndNeverStrandsADrainPermit() {
        val outage = IllegalStateException("platform storage unavailable")
        Mockito.`when`(storageResolver.getPlatformStorage()).thenThrow(outage)
        repeat(11) {
            assertSame(outage, assertThrows(IllegalStateException::class.java) { service.moveToEventStorage() })
        }
        Mockito.verify(storageResolver, Mockito.times(11)).getPlatformStorage()
    }

    @Test
    fun failedMovePermitAcquireDoesNotInflateSemaphore() {
        val semaphore = Semaphore(1)
        assertTrue(semaphore.tryAcquire())
        var executed = false

        assertFalse(
            semaphore.tryRunWithPermit {
                executed = true
            },
        )

        assertFalse(executed)
        assertEquals("A failed acquire must not release a permit it never owned", 0, semaphore.availablePermits())
        semaphore.release()
        assertEquals(1, semaphore.availablePermits())
    }

    @Test
    fun acquiredMovePermitIsRestoredWhenDrainFails() {
        val semaphore = Semaphore(1)

        assertThrows(IllegalStateException::class.java) {
            semaphore.tryRunWithPermit {
                error("drain failed")
            }
        }

        assertEquals("The acquired permit must be restored after a drain failure", 1, semaphore.availablePermits())
    }
}
