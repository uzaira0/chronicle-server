package com.openlattice.chronicle.storage

import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.contract.seedEnrolledParticipant
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.data.CaptureWindow
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.exceptions.CollectionUnavailableException
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.delete.ServerSweepTestFixture
import com.openlattice.chronicle.services.studies.CollectionHaltedException
import com.openlattice.chronicle.services.studies.CollectionUploadRequest
import com.openlattice.chronicle.services.studies.ParticipantCollectionAcknowledgmentService
import com.openlattice.chronicle.services.studies.ParticipantProcessingService
import com.openlattice.chronicle.services.studies.StudyLifecycleService
import com.openlattice.chronicle.services.studies.StudyLimitsManager
import com.openlattice.chronicle.services.studies.StudyLimitsService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.upload.UploadDiagnosticsUploadService
import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.study.StudyLifecycleStatus
import com.openlattice.chronicle.study.StudyLimits
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * The server admits uploads by server facts only; which rows were captured inside a closed window is the phone's
 * decision, on its clock. The server never compares phone capture times with its own cutoffs.
 */
class ArchivedCaptureGateTest : ServerSweepTestFixture() {
    @Test fun lifecycleAndParticipationMatrixAdmitsEveryQueuedCaptureWhileStopped() {
        postgres.createConnection("").use { connection ->
            connection.autoCommit = false
            try {
                verifyLifecycleMatrix(connection)
            } finally { connection.rollback() }
        }
    }

    /**
     * Generated stop histories: closed windows and a pause never make the server refuse an upload. The gate takes no
     * capture time at all, so a skewed phone stamp cannot be judged against a server instant.
     */
    @Test fun noPhoneStampIsJudgedAgainstAServerInstant() {
        val random = java.util.Random(20261028)
        postgres.createConnection("").use { connection ->
            connection.autoCommit = false
            try {
                repeat(40) { run ->
                    val study = seed(connection, ParticipationStatus.ENROLLED)
                    val now = serverNow(connection)
                    var cursor = now.minusDays(3)
                    repeat(random.nextInt(4)) {
                        val start = cursor.plusMinutes(1 + random.nextInt(600).toLong())
                        val end = start.plusMinutes(1 + random.nextInt(600).toLong())
                        addClosedWindow(connection, study, start, end)
                        cursor = end
                    }
                    if (random.nextBoolean()) {
                        transitionParticipation(connection, study, ParticipationStatus.PAUSED)
                        setCutoff(connection, study, cursor.plusMinutes(5))
                    }
                    ParticipantCollectionGate(connection).requireCollectionAvailable(study, "capture")
                    assertTrue("run $run", ParticipantCollectionGate(connection).isActive(study, "capture", allowQueuedCaptures = true))
                }
            } finally { connection.rollback() }
        }
    }

    @Test fun captureWindowsPublishEveryStopOnTheServerClock() {
        postgres.createConnection("").use { connection ->
            connection.autoCommit = false
            try {
                val study = seed(connection, ParticipationStatus.ENROLLED)
                val now = serverNow(connection)
                val gate = ParticipantCollectionGate(connection)
                assertEquals(emptyList<CaptureWindow>(), gate.captureWindows(study, "capture"))

                transitionParticipation(connection, study, ParticipationStatus.PAUSED)
                setCutoff(connection, study, now.minusHours(3))
                assertEquals("a pause is open while it lasts", listOf(CaptureWindow(utc(now.minusHours(3)))),
                    gate.captureWindows(study, "capture"))

                transitionParticipation(connection, study, ParticipationStatus.ENROLLED)
                val paused = CaptureWindow(utc(now.minusHours(3)), utc(now))
                assertEquals("a resume closes it at the server's instant", listOf(paused), gate.captureWindows(study, "capture"))

                val archived = seed(connection, ParticipationStatus.ENROLLED)
                connection.prepareStatement("UPDATE studies SET lifecycle_status = 'ARCHIVED' WHERE study_id = ?").use {
                    it.setObject(1, archived); it.executeUpdate()
                }
                connection.prepareStatement("UPDATE studies SET collection_closed_at = ? WHERE study_id = ?").use {
                    it.setObject(1, now.minusHours(1)); it.setObject(2, archived); it.executeUpdate()
                }
                assertEquals("an archive is open while it lasts", listOf(CaptureWindow(utc(now.minusHours(1)))),
                    gate.captureWindows(archived, "capture"))
                connection.prepareStatement("UPDATE studies SET lifecycle_status = 'ACTIVE' WHERE study_id = ?").use {
                    it.setObject(1, archived); it.executeUpdate()
                }
                assertEquals("reopening closes it at the server's instant",
                    listOf(CaptureWindow(utc(now.minusHours(1)), utc(now))), gate.captureWindows(archived, "capture"))
            } finally { connection.rollback() }
        }
    }

    /** An unblock leaves the processing block behind as a closed window. */
    @Test fun processingUnblockLeavesAClosedCaptureWindow() {
        val study = UUID.randomUUID()
        seed(study, "blocked")
        TestSecurityUtils.setupSecurityContext()
        try {
            pool("processing-window").use { hds ->
                val processing = ParticipantProcessingService(resolver(hds), Mockito.mock(AuditingManager::class.java))
                processing.block(study, "blocked", "Participant asked for a review", revision(hds, study))
                val blockedAt = hds.connection.use { connection ->
                    val windows = ParticipantCollectionGate(connection).captureWindows(study, "blocked")
                    assertEquals("a block is open while it lasts", 1, windows.size)
                    assertEquals(null, windows.single().end)
                    windows.single().start
                }
                processing.unblock(study, "blocked", revision(hds, study))
                hds.connection.use { connection ->
                    val window = ParticipantCollectionGate(connection).captureWindows(study, "blocked").single()
                    assertEquals(blockedAt, window.start)
                    assertTrue("the block closes at the unblock", window.end!!.isAfter(blockedAt))
                }
            }
        } finally { TestSecurityUtils.clearSecurityContext() }
    }

    @Test fun automaticArchiveStillCommitsQueuedHistoricalUploads() {
        val study = UUID.randomUUID()
        val device = UUID.randomUUID()
        val captured = OffsetDateTime.now().minusDays(1)
        val event = AndroidUploadDiagnosticEvent(UUID.randomUUID().toString(), captured.toLocalDate(),
            "BATTERY", "TIMEOUT", 1, captured, captured)
        seed(study, "backlog")
        TestSecurityUtils.setupSecurityContext()
        try {
            pool("archived-backlog").use { hds ->
                val storage = resolver(hds)
                val limits = StudyLimitsService(storage, Mockito.mock(HazelcastInstance::class.java, Mockito.RETURNS_DEEP_STUBS))
                hds.connection.use { limits.initializeStudyLimits(it, study, StudyLimits(studyEnds = OffsetDateTime.now().minusMinutes(1))) }
                automaticArchive(storage)
                val acknowledgments = ParticipantCollectionAcknowledgmentService(storage)
                acknowledgments.withCollectionUploadRecheck(CollectionUploadRequest(study, "backlog", device, null, null), emptySet(),
                    allowPartial = false, allowQueuedCaptures = true) {
                    assertEquals(setOf(UUID.fromString(event.id)),
                        UploadDiagnosticsUploadService(storage).upload(study, "backlog", device, listOf(event)).acceptedIds)
                }
                assertUploaded(hds, study, event)
                assertOnlyQueuedUploadsAreAdmitted(acknowledgments, study, device)
            }
        } finally { TestSecurityUtils.clearSecurityContext() }
    }

    @Test fun onlyAnEndTheResearcherSetPausesOrArchives() {
        val openEnded = UUID.randomUUID()
        val ended = UUID.randomUUID()
        seed(openEnded, "open"); seed(ended, "ended")
        // Limits created two years ago with no researcher-set end: no default deadline may have passed since.
        val createdLongAgo = Mockito.mockStatic(OffsetDateTime::class.java, Mockito.CALLS_REAL_METHODS).use { clock ->
            val past = OffsetDateTime.now().minusYears(2)
            clock.`when`<OffsetDateTime> { OffsetDateTime.now() }.thenReturn(past)
            StudyLimits()
        }
        TestSecurityUtils.setupSecurityContext()
        try {
            pool("open-ended").use { hds ->
                val storage = resolver(hds)
                val limits = StudyLimitsService(storage, Mockito.mock(HazelcastInstance::class.java, Mockito.RETURNS_DEEP_STUBS))
                hds.connection.use {
                    limits.initializeStudyLimits(it, openEnded, createdLongAgo)
                    limits.initializeStudyLimits(it, ended, StudyLimits(studyEnds = OffsetDateTime.now().minusMinutes(1)))
                }
                assertEquals(setOf(ended), limits.getStudiesExceedingDurationLimit().intersect(setOf(openEnded, ended)))
                automaticArchive(storage)
                assertEquals(StudyLifecycleStatus.ACTIVE.name, lifecycle(hds, openEnded))
                assertEquals(StudyLifecycleStatus.ARCHIVED.name, lifecycle(hds, ended))
            }
        } finally { TestSecurityUtils.clearSecurityContext() }
    }

    private fun lifecycle(hds: HikariDataSource, study: UUID): String = hds.connection.use { connection ->
        connection.prepareStatement("SELECT lifecycle_status FROM studies WHERE study_id = ?").use {
            it.setObject(1, study)
            it.executeQuery().use { rows -> assertTrue(rows.next()); rows.getString(1) }
        }
    }

    private fun assertUploaded(hds: HikariDataSource, study: UUID, event: AndroidUploadDiagnosticEvent) {
        hds.connection.use { connection ->
            connection.prepareStatement("SELECT event_id FROM upload_diagnostics WHERE study_id = ?").use {
                it.setObject(1, study)
                it.executeQuery().use { rows ->
                    assertTrue(rows.next()); assertEquals(event.id, rows.getString(1)); assertFalse(rows.next())
                }
            }
        }
    }

    /** Archived: a queued upload is admitted; a route that does not drain queues is refused. */
    private fun assertOnlyQueuedUploadsAreAdmitted(acknowledgments: ParticipantCollectionAcknowledgmentService, study: UUID,
                                                   device: UUID) {
        assertTrue(acknowledgments.withCollectionUploadRecheck(CollectionUploadRequest(study, "backlog", device, null, null), emptySet(),
            allowPartial = false, allowQueuedCaptures = true) { true })
        assertThrows(CollectionHaltedException::class.java) {
            acknowledgments.withCollectionUploadRecheck(CollectionUploadRequest(study, "backlog", device, null, null), emptySet(),
                allowPartial = false, allowQueuedCaptures = false) {
                throw AssertionError("A route that does not drain queues wrote while archived")
            }
        }
    }

    private fun automaticArchive(storage: StorageResolver) {
        val ids = Mockito.mock(HazelcastIdGenerationService::class.java)
        Mockito.`when`(ids.getNextId()).thenAnswer { UUID.randomUUID() }
        StudyLifecycleService(storage, Mockito.mock(StudyService::class.java), Mockito.mock(AuthorizationManager::class.java),
            ids, Mockito.mock(AuditingManager::class.java), Mockito.mock(DataDeletionOrchestrator::class.java),
            Mockito.mock(WebhookService::class.java), Mockito.mock(StudyLimitsManager::class.java)).autoArchiveExpiredStudies()
    }

    private fun verifyLifecycleMatrix(connection: Connection) {
        for (lifecycle in StudyLifecycleStatus.entries) for (participation in ParticipationStatus.entries) {
            verifyState(connection, lifecycle, participation)
        }
    }

    private fun verifyState(connection: Connection, lifecycle: StudyLifecycleStatus, participation: ParticipationStatus) {
        val study = seed(connection, participation)
        connection.prepareStatement("UPDATE studies SET lifecycle_status = ? WHERE study_id = ?").use {
            it.setString(1, lifecycle.name); it.setObject(2, study); it.executeUpdate()
        }
        // Withdrawal and Mark not enrolled are a hard stop: NOT_ENROLLED uploads nothing.
        val eligibleParticipant = participation in setOf(ParticipationStatus.ENROLLED,
            ParticipationStatus.PAUSED, ParticipationStatus.COLLECTION_COMPLETED)
        val eligibleStudy = lifecycle in setOf(StudyLifecycleStatus.ACTIVE, StudyLifecycleStatus.ARCHIVED)
        val stopped = lifecycle == StudyLifecycleStatus.ARCHIVED || participation == ParticipationStatus.PAUSED ||
            participation == ParticipationStatus.COLLECTION_COMPLETED
        val accepted = ParticipantCollectionGate(connection).isActive(study, "capture", allowQueuedCaptures = true)
        assertEquals("$lifecycle / $participation", eligibleStudy && eligibleParticipant, accepted)
        assertEquals("$lifecycle / $participation collecting", eligibleStudy && eligibleParticipant && !stopped,
            ParticipantCollectionGate(connection).isActive(study, "capture"))
        if (accepted) {
            ParticipantCollectionGate(connection).requireCollectionAvailable(study, "capture")
        } else {
            assertThrows(CollectionUnavailableException::class.java) {
                ParticipantCollectionGate(connection).requireCollectionAvailable(study, "capture")
            }
        }
        connection.prepareStatement("UPDATE studies SET lifecycle_status = ? WHERE study_id = ?").use {
            it.setString(1, StudyLifecycleStatus.ACTIVE.name); it.setObject(2, study); it.executeUpdate()
        }
        if (lifecycle == StudyLifecycleStatus.ARCHIVED) assertUnarchivedCutoff(connection, study)
        if (participation == ParticipationStatus.ENROLLED) {
            assertTrue(ParticipantCollectionGate(connection).isActive(study, "capture"))
        }
    }

    private fun transitionParticipation(connection: Connection, study: UUID, status: ParticipationStatus): OffsetDateTime? =
        connection.prepareStatement(
            "UPDATE study_participants SET participation_status = ? WHERE study_id = ? RETURNING capture_cutoff",
        ).use {
            it.setString(1, status.name); it.setObject(2, study)
            it.executeQuery().use { rows -> assertTrue(rows.next()); rows.getObject(1, OffsetDateTime::class.java) }
        }

    private fun seed(connection: Connection, participation: ParticipationStatus): UUID = UUID.randomUUID().also { study ->
        connection.prepareStatement("INSERT INTO studies (study_id, title) VALUES (?, 'capture matrix')").use {
            it.setObject(1, study); it.executeUpdate()
        }
        // Erasure removes the row; ERASED is a display status, not a persisted enrollment state.
        if (participation == ParticipationStatus.ERASED) return@also
        seedEnrolledParticipant(connection, study, "capture")
        transitionParticipation(connection, study, participation)
    }

    private fun serverNow(connection: Connection): OffsetDateTime = connection.prepareStatement("SELECT now()").use {
        it.executeQuery().use { rows -> assertTrue(rows.next()); rows.getObject(1, OffsetDateTime::class.java) }
    }

    private fun utc(time: OffsetDateTime): OffsetDateTime = time.toInstant().atOffset(ZoneOffset.UTC)

    private fun setCutoff(connection: Connection, study: UUID, cutoff: OffsetDateTime) {
        connection.prepareStatement("UPDATE study_participants SET capture_cutoff = ? WHERE study_id = ?").use {
            it.setObject(1, cutoff); it.setObject(2, study); it.executeUpdate()
        }
    }

    private fun addClosedWindow(connection: Connection, study: UUID, start: OffsetDateTime, end: OffsetDateTime) {
        connection.prepareStatement(
            "UPDATE study_participants SET closed_capture_windows = tstzmultirange(tstzrange(?, ?)) WHERE study_id = ?",
        ).use { it.setObject(1, start); it.setObject(2, end); it.setObject(3, study); it.executeUpdate() }
    }

    private fun revision(hds: HikariDataSource, study: UUID): Long = hds.connection.use { connection ->
        connection.prepareStatement("SELECT status_revision FROM study_participants WHERE study_id = ?").use {
            it.setObject(1, study)
            it.executeQuery().use { rows -> assertTrue(rows.next()); rows.getLong(1) }
        }
    }

    private fun assertUnarchivedCutoff(connection: Connection, study: UUID) {
        connection.prepareStatement("SELECT collection_closed_at FROM studies WHERE study_id = ?").use {
            it.setObject(1, study)
            it.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals(null, rows.getObject(1)) }
        }
    }
}
