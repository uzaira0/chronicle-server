package com.openlattice.chronicle.services.studies

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.services.upload.UploadDiagnosticsUploadService
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The upload gate committed and released its per-device lock before the controller wrote, so a
 * consent DECLINE or a required-settings revision landing in between still let the batch persist.
 * These are the real-PostgreSQL contracts for the atomic recheck that closed that window.
 */
class CollectionHaltWriteAtomicityTest {

    companion object {
        private lateinit var db: ConsentGatePostgres
        private val postgres get() = db.postgres
        private val storageResolver get() = db.storageResolver
        private val service get() = db.service
        private const val PARTICIPANT_ID = ConsentGatePostgres.PARTICIPANT_ID
        private val mapper = ObjectMappers.getJsonMapper()

        @BeforeClass
        @JvmStatic
        fun setUp() {
            db = ConsentGatePostgres("chronicle_halt_atomicity")
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            if (Companion::db.isInitialized) db.close()
        }
    }

    /**
     * One clock: a paused or completed participant uploads every queued row, because the phone
     * drops rows inside its windows on its own clock. Mark not enrolled is a hard stop: nothing uploads.
     */
    @Test
    fun pausedAndCompletedAcceptQueuedCapturesWhileMarkNotEnrolledRefusesEveryUpload() {
        for (status in listOf("PAUSED", "COLLECTION_COMPLETED", "NOT_ENROLLED")) {
            val studyId = db.newStudy(required = false, settingsVersion = 1)
            val device = UUID.randomUUID()
            stopParticipant(studyId, status)
            val probe = UUID.randomUUID()
            val upload = { guarded(studyId, device) { db.insertProbe(probe, studyId) } }
            val refused = status == "NOT_ENROLLED"
            if (refused) assertThrows(CollectionHaltedException::class.java) { upload() } else upload()
            assertEquals("$status stores a queued row", !refused, db.probeExists(probe))
        }
        // Every upload route's shape is refused after Mark not enrolled, even for a module the phone had accepted.
        val studyId = db.newStudy(required = false, settingsVersion = 1)
        val device = UUID.randomUUID()
        val battery = setOf(CollectionModuleId.BATTERY_TELEMETRY)
        postgres.createConnection("").use { connection ->
            db.seedDecision(connection, studyId, device, UUID.randomUUID(),
                CollectionAcknowledgment(battery, OffsetDateTime.now().minusHours(1), settingsVersion = 1, decisionSeq = 1))
        }
        stopParticipant(studyId, "NOT_ENROLLED")
        for (modules in listOf(emptySet(), battery)) for (queued in listOf(true, false)) for (partial in listOf(true, false)) {
            val probe = UUID.randomUUID()
            assertThrows("modules=$modules queued=$queued partial=$partial", CollectionHaltedException::class.java) {
                service.withCollectionUploadRecheck(CollectionUploadRequest(studyId, PARTICIPANT_ID, device, 1, 1L), modules,
                    partial, queued) { db.insertProbe(probe, studyId) }
            }
            assertFalse(db.probeExists(probe))
        }
    }

    /** Sets [status] and returns the server's stop instant. */
    private fun stopParticipant(studyId: UUID, status: String): java.time.Instant = postgres.createConnection("").use { connection ->
        connection.prepareStatement(
            "UPDATE study_participants SET participation_status = ? WHERE study_id = ? RETURNING capture_cutoff").use {
            it.setString(1, status); it.setObject(2, studyId)
            it.executeQuery().use { rows -> assertTrue(rows.next()); rows.getObject(1, OffsetDateTime::class.java).toInstant() }
        }
    }

    /**
     * A standing decline of a required module is a withdrawal. A row
     * captured while the module was still accepted does not upload either, with or without a settings version.
     */
    @Test
    fun rowsCapturedBeforeARequiredDeclineDoNotUpload() {
        val studyId = db.newStudy(required = true, settingsVersion = 1)
        val device = UUID.randomUUID()
        val key = UUID.randomUUID()
        val module = CollectionModuleId.BATTERY_TELEMETRY
        val acceptedAt = OffsetDateTime.now().minusHours(2)
        postgres.createConnection("").use { connection ->
            db.seedDecision(connection, studyId, device, key,
                CollectionAcknowledgment(setOf(module), acceptedAt, settingsVersion = 1, trigger = ConsentTrigger.ENROLLMENT))
            db.seedDecision(connection, studyId, device, key, CollectionAcknowledgment(emptySet(), OffsetDateTime.now(),
                setOf(module), settingsVersion = 1, trigger = ConsentTrigger.PARTICIPANT_TOGGLE))
        }
        val capturedAt = acceptedAt.plusMinutes(30)
        val event = AndroidUploadDiagnosticEvent(UUID.randomUUID().toString(), capturedAt.toLocalDate(),
            "BATTERY", "TIMEOUT", 1, capturedAt, capturedAt)
        for ((version, seq) in listOf(null to null, 1 to 1L, 1 to 2L)) {
            assertThrows("settings version $version, decision $seq", CollectionHaltedException::class.java) {
                guarded(studyId, device, setOf(module), version, seq, allowPartial = true) {
                    UploadDiagnosticsUploadService(storageResolver).upload(studyId, PARTICIPANT_ID, device, listOf(event))
                }
            }
        }
        val probe = UUID.randomUUID()
        assertThrows(CollectionHaltedException::class.java) {
            guarded(studyId, device, setOf(module), 1, 1L) { db.insertProbe(probe, studyId) }
        }
        assertFalse(db.diagnosticStoredOnce(studyId, event.id))
        assertFalse(db.probeExists(probe))
    }

    @Test fun sharedConsentTableThroughTheService() {
        val rows = mapper.readTree(java.io.File("../chronicle-models/fixtures/collection-consent.json"))
        val module = CollectionModuleId.BATTERY_TELEMETRY
        rows.forEach { row ->
            val history = row["revisions"].map { mapper.treeToValue(it, AndroidDataCollectionSetting::class.java) }
            val initial = history.first().effectiveModules().getValue(module)
            val decision = IssuedCollectionDecisionState.entries.singleOrNull { it.name == row["decision"].asText() }
            val study = db.newStudy(initial.required, 1, history.first())
            val device = UUID.randomUUID()
            val key = UUID.randomUUID()
            postgres.createConnection("").use { connection ->
                if (decision != null) {
                    db.seedDecision(connection, study, device, key, CollectionAcknowledgment(
                        if (decision == IssuedCollectionDecisionState.ACCEPTED) setOf(module) else emptySet(), OffsetDateTime.now(),
                        if (decision == IssuedCollectionDecisionState.DECLINED) setOf(module) else emptySet(), settingsVersion = 1))
                }
                history.drop(1).forEach { setting -> db.updateCollectionSettings(connection, study,
                    setting.effectiveModules().getValue(module).required, setting.settingsVersion, collection = setting) }
            }
            assertEquals(row["name"].asText(), row["halted"].asBoolean(),
                service.isCollectionHalted(study, PARTICIPANT_ID, device))
            val expectedAccepted = row["survives"].asBoolean() && decision == IssuedCollectionDecisionState.ACCEPTED
            if (expectedAccepted) {
                guarded(study, device, setOf(module), history.last().settingsVersion) {
                    assertEquals(setOf(module), it.acceptedModules)
                }
            } else {
                assertThrows(row["name"].asText(), CollectionHaltedException::class.java) {
                    guarded(study, device, setOf(module), history.last().settingsVersion) { }
                }
            }
        }
    }

    @Test fun deviceSettingsExposeMissedDisableAndReenableConsentEpoch() {
        val study = db.newStudy(required = false, settingsVersion = 1)
        postgres.createConnection("").use { connection ->
            db.updateCollectionSettings(connection, study, required = false, settingsVersion = 2, enabled = false)
            db.updateCollectionSettings(connection, study, required = false, settingsVersion = 3)
        }
        assertEquals(3, checkNotNull(service.collectionSettingsForDevice(study)).effectiveModules()
            .getValue(CollectionModuleId.BATTERY_TELEMETRY).consentEpoch)
    }

    @Test fun requirednessChangeDrainsInterveningCapturesAfterPhoneAcknowledgmentOnRetry() {
        val study = db.newStudy(required = false, settingsVersion = 1)
        val device = UUID.randomUUID()
        val key = UUID.randomUUID()
        val module = CollectionModuleId.BATTERY_TELEMETRY
        val captured = OffsetDateTime.now()
        postgres.createConnection("").use { connection ->
            db.seedDecision(connection, study, device, key,
                CollectionAcknowledgment(setOf(module), captured, settingsVersion = 1, decisionSeq = 1))
            db.updateCollectionSettings(connection, study, required = true, settingsVersion = 2)
            db.seedDecision(connection, study, device, key, CollectionAcknowledgment(setOf(module), captured,
                settingsVersion = 2, trigger = ConsentTrigger.SETTINGS_CHANGE, decisionSeq = 2))
        }
        val event = AndroidUploadDiagnosticEvent(UUID.randomUUID().toString(), captured.toLocalDate(),
            "BATTERY", "TIMEOUT", 1, captured, captured)
        repeat(2) {
            guarded(study, device, setOf(module), 1, 1L) { authorization ->
                assertEquals(setOf(module), authorization.acceptedModules)
                UploadDiagnosticsUploadService(storageResolver).upload(study, PARTICIPANT_ID, device, listOf(event))
            }
        }
        assertTrue(db.diagnosticStoredOnce(study, event.id))
        postgres.createConnection("").use { connection ->
            db.seedDecision(connection, study, device, key, CollectionAcknowledgment(emptySet(), captured,
                setOf(module), settingsVersion = 2, trigger = ConsentTrigger.SETTINGS_CHANGE, decisionSeq = 3))
        }
        assertTrue(service.isCollectionHalted(study, PARTICIPANT_ID, device))
        assertThrows(CollectionHaltedException::class.java) { guarded(study, device, setOf(module), 2, 2L) { } }
    }

    /** Settings written without a ledger revision must not fail every phone's settings fetch. */
    @Test fun deviceSettingsForceReconsentWhenTheLedgerLagsTheProjection() {
        val study = db.newStudy(required = false, settingsVersion = 1)
        postgres.createConnection("").use { connection ->
            db.updateCollectionSettings(connection, study, required = true, settingsVersion = 2)
            // Dropping the collection feature changes the projection; the ledger keeps v2 as issued.
            connection.prepareStatement("UPDATE studies SET modules = '{}'::jsonb WHERE study_id = ?").use {
                it.setObject(1, study); assertEquals(1, it.executeUpdate())
            }
        }
        val modules = checkNotNull(service.collectionSettingsForDevice(study)).effectiveModules()
        assertEquals("A diverged module forces re-consent", 2, modules.getValue(CollectionModuleId.BATTERY_TELEMETRY).consentEpoch)
        assertEquals("An unchanged module keeps its ledger epoch", 1,
            modules.getValue(CollectionModuleId.UPLOAD_TELEMETRY).consentEpoch)
    }

    /** A disable stops new rows, never the ones captured while the module was enabled; nothing is held. */
    @Test
    fun aHistoricalVersionAuthorizesItsOwnRowsAndADisableDiscardsLaterOnes() {
        val studyId = db.newStudy(required = false, settingsVersion = 1)
        val randomDeviceId = UUID.randomUUID()
        val modules = setOf(CollectionModuleId.BATTERY_TELEMETRY)
        postgres.createConnection("").use { connection ->
            db.seedDecision(connection, studyId, randomDeviceId, UUID.randomUUID(),
                CollectionAcknowledgment(modules, OffsetDateTime.now(), settingsVersion = 1, decisionSeq = 1))
            db.updateCollectionSettings(connection, studyId, required = false, settingsVersion = 2, enabled = false)
        }
        val oldProbe = UUID.randomUUID()
        guarded(studyId, randomDeviceId, modules, 1, 1L) { db.insertProbe(oldProbe, studyId) }
        assertTrue("The pre-disable offline queue still flushes", db.probeExists(oldProbe))
        for (version in listOf(null, 2)) {
            val freshProbe = UUID.randomUUID()
            assertThrows(CollectionHaltedException::class.java) {
                guarded(studyId, randomDeviceId, modules, version, 1L) { db.insertProbe(freshProbe, studyId) }
            }
            assertFalse("A disabled module authorizes no row captured after the disable", db.probeExists(freshProbe))
            guarded(studyId, randomDeviceId, modules, version, 1L, allowPartial = true) { authorization ->
                assertEquals(modules, authorization.discardedModules)
                assertTrue(authorization.acceptedModules.isEmpty())
            }
        }
    }

    @Test
    fun `an optional module without acceptance cannot commit a write`() {
        val studyId = db.newStudy(required = false, settingsVersion = 1)
        val randomDeviceId = UUID.randomUUID()
        val probeId = UUID.randomUUID()
        assertFalse(service.isCollectionHalted(studyId, PARTICIPANT_ID, randomDeviceId))
        assertThrows(CollectionHaltedException::class.java) {
            guarded(studyId, randomDeviceId, setOf(CollectionModuleId.BATTERY_TELEMETRY)) {
                db.insertProbe(probeId, studyId)
            }
        }
        assertFalse(db.probeExists(probeId))
    }

    @Test
    fun `halt recorded after the gate and before the write rejects the write`() {
        val studyId = db.newStudy(required = false, settingsVersion = 1)
        val randomDeviceId = UUID.randomUUID()

        assertFalse(
            "gate must pass while no enabled module is required",
            service.isCollectionHalted(studyId, PARTICIPANT_ID, randomDeviceId),
        )

        // The concurrent consent/settings transaction lands in the window the gate left open.
        recordHalt(studyId, settingsVersion = 2, device = randomDeviceId)

        val probeId = UUID.randomUUID()
        assertThrows(CollectionHaltedException::class.java) {
            guarded(studyId, randomDeviceId) {
                db.insertProbe(probeId, studyId)
            }
        }
        assertFalse("the write must be rolled back with the recheck", db.probeExists(probeId))
    }

    /**
     * Negative control for the test above: the same sequence with the atomic recheck removed is
     * exactly the reported defect — the write commits after the halt was recorded.
     */
    @Test
    fun `without the atomic recheck the same sequence persists the write`() {
        val studyId = db.newStudy(required = false, settingsVersion = 1)
        val randomDeviceId = UUID.randomUUID()

        assertFalse(service.isCollectionHalted(studyId, PARTICIPANT_ID, randomDeviceId))
        recordHalt(studyId, settingsVersion = 2, device = randomDeviceId)
        assertTrue(
            "the halt must be visible to the predicate",
            service.isCollectionHalted(studyId, PARTICIPANT_ID, randomDeviceId),
        )

        val probeId = UUID.randomUUID()
        db.insertProbe(probeId, studyId)
        assertTrue(db.probeExists(probeId))
    }

    @Test
    fun `nested transaction control leaves the guarded write for the owner to commit`() {
        val studyId = db.newStudy(required = false, settingsVersion = 1)
        val randomDeviceId = UUID.randomUUID()
        val probeId = UUID.randomUUID()

        guarded(studyId, randomDeviceId) {
            db.insertProbe(probeId, studyId)
            storageResolver.getPlatformStorage().connection.use { borrowed ->
                borrowed.commit()
                borrowed.rollback()
                borrowed.autoCommit = true
            }
            assertFalse("Only the guard owner may publish the write", db.probeExists(probeId))
        }
        assertTrue("The guard owner commits after write returns", db.probeExists(probeId))
    }

    @Test
    fun `halt recorded after the write commits does not affect it`() {
        val studyId = db.newStudy(required = false, settingsVersion = 1)
        val randomDeviceId = UUID.randomUUID()
        val probeId = UUID.randomUUID()

        guarded(studyId, randomDeviceId) {
            db.insertProbe(probeId, studyId)
        }
        assertTrue(db.probeExists(probeId))

        recordHalt(studyId, settingsVersion = 2, device = randomDeviceId)

        assertTrue(service.isCollectionHalted(studyId, PARTICIPANT_ID, randomDeviceId))
        assertTrue("a later halt must not retract a committed write", db.probeExists(probeId))
    }

    /**
     * The write is inside the serialization window: while the guard's transaction is open, a
     * settings write that would record a halt blocks on the `FOR SHARE` row lock the predicate
     * took, so it can only land strictly before or strictly after the guarded write.
     */
    @Test
    fun `a settings halt cannot commit while a guarded write is in flight`() {
        val studyId = db.newStudy(required = false, settingsVersion = 1)
        val randomDeviceId = UUID.randomUUID()
        val probeId = UUID.randomUUID()
        postgres.createConnection("").use { connection ->
            db.seedDecision(connection, studyId, randomDeviceId, UUID.randomUUID(), CollectionAcknowledgment(emptySet(),
                OffsetDateTime.now(), setOf(CollectionModuleId.BATTERY_TELEMETRY), settingsVersion = 1))
        }
        val insideGuard = CountDownLatch(1)
        val haltAttempted = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val halt = executor.submit {
                insideGuard.await(30, TimeUnit.SECONDS)
                haltAttempted.countDown()
                // Uses its own physical connection so it cannot ride the guard's transaction.
                postgres.createConnection("").use { connection ->
                    db.updateCollectionSettings(connection, studyId, required = true, settingsVersion = 2)
                }
            }

            guarded(studyId, randomDeviceId) {
                insideGuard.countDown()
                assertTrue(haltAttempted.await(30, TimeUnit.SECONDS))
                Thread.sleep(TimeUnit.SECONDS.toMillis(1))
                assertFalse("the halt must not commit while the guarded write is open", halt.isDone)
                db.insertProbe(probeId, studyId)
            }

            halt.get(30, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        assertTrue(db.probeExists(probeId))
        assertTrue(service.isCollectionHalted(studyId, PARTICIPANT_ID, randomDeviceId))
    }

    /**
     * The diagnostics service must leave autocommit off, or the guard's own commit() throws
     * "Cannot commit when autoCommit is enabled" and every non-empty batch answers 500. The batch
     * commits through the pinned connection like any other upload.
     */
    @Test
    fun `upload diagnostics commit inside the recheck`() {
        val studyId = db.newStudy(required = false, settingsVersion = 1)
        val randomDeviceId = UUID.randomUUID()
        val now = OffsetDateTime.now()
        val event = AndroidUploadDiagnosticEvent(
            id = UUID.randomUUID().toString(),
            day = LocalDate.now(),
            moduleFamily = "USAGE_LIFECYCLE",
            issueCode = "TIMEOUT",
            count = 1,
            firstOccurredAt = now,
            lastOccurredAt = now,
        )

        val accepted = guarded(studyId, randomDeviceId) {
            val uploaded = UploadDiagnosticsUploadService(storageResolver).upload(studyId, PARTICIPANT_ID, randomDeviceId, listOf(event))
            assertFalse("The nested upload commit must wait for the guard owner", db.diagnosticStoredOnce(studyId, event.id))
            uploaded
        }

        assertEquals(setOf(UUID.fromString(event.id)), accepted.acceptedIds)
        assertTrue("the batch must be committed by the guard", db.diagnosticStoredOnce(studyId, event.id))
    }

    @Test fun firstEnrollmentPreservesPreAcknowledgmentHistory() {
        val study = db.newStudy(required = false, settingsVersion = 1)
        val device = UUID.randomUUID()
        val module = CollectionModuleId.BATTERY_TELEMETRY
        val acknowledgedAt = OffsetDateTime.now()
        postgres.createConnection("").use { connection ->
            db.seedDecision(connection, study, device, UUID.randomUUID(), CollectionAcknowledgment(setOf(module), acknowledgedAt,
                settingsVersion = 1, trigger = ConsentTrigger.ENROLLMENT, decisionSeq = 1))
        }
        val capturedAt = acknowledgedAt.minusDays(1)
        for ((version, seq) in listOf(null to null, 1 to 1L)) {
            val event = AndroidUploadDiagnosticEvent(UUID.randomUUID().toString(), capturedAt.toLocalDate(),
                "BATTERY", "TIMEOUT", 1, capturedAt, capturedAt)
            guarded(study, device, setOf(module), version, seq, allowPartial = true) { authorization ->
                assertEquals(setOf(module), authorization.acceptedModules)
                UploadDiagnosticsUploadService(storageResolver).upload(study, PARTICIPANT_ID, device, listOf(event))
            }
            assertTrue("First enrollment authorizes history predating both settings and consent", db.diagnosticStoredOnce(study, event.id))
        }
    }

    /** Rows stamped with the decline's sequence are discarded; rows stamped after the re-acceptance are stored. */
    @Test fun resumedConsentCannotAuthorizeCapturesFromTheRequiredDeclineHalt() {
        val study = db.newStudy(required = false, settingsVersion = 1)
        val device = UUID.randomUUID()
        val key = UUID.randomUUID()
        val battery = CollectionModuleId.BATTERY_TELEMETRY
        val now = OffsetDateTime.now()
        postgres.createConnection("").use { connection ->
            db.seedDecision(connection, study, device, key,
                CollectionAcknowledgment(emptySet(), now, setOf(battery), settingsVersion = 1, decisionSeq = 1))
            db.updateCollectionSettings(connection, study, required = true, settingsVersion = 2)
            db.seedDecision(connection, study, device, key, CollectionAcknowledgment(setOf(battery), now,
                settingsVersion = 2, trigger = ConsentTrigger.PARTICIPANT_TOGGLE, decisionSeq = 2))
        }
        val inside = AndroidUploadDiagnosticEvent(UUID.randomUUID().toString(), now.toLocalDate(), "BATTERY", "TIMEOUT", 1, now, now)
        val outside = inside.copy(id = UUID.randomUUID().toString())
        for ((event, seq) in listOf(inside to 1L, outside to 2L)) {
            guarded(study, device, setOf(battery), 2, seq, allowPartial = true) { authorization ->
                if (battery in authorization.acceptedModules) {
                    UploadDiagnosticsUploadService(storageResolver).upload(study, PARTICIPANT_ID, device, listOf(event))
                }
            }
        }
        assertFalse("Rows captured under the standing required decline are not stored", db.diagnosticStoredOnce(study, inside.id))
        assertTrue("Rows captured after the re-acceptance are stored", db.diagnosticStoredOnce(study, outside.id))
    }

    @Test fun aHistoricalDeclineDiscardsOnlyTheRowsCapturedUnderIt() {
        val study = db.newStudy(required = true, settingsVersion = 1)
        val device = UUID.randomUUID()
        val key = UUID.randomUUID()
        val module = CollectionModuleId.BATTERY_TELEMETRY
        postgres.createConnection("").use { connection ->
            db.seedDecision(connection, study, device, key, CollectionAcknowledgment(setOf(module), OffsetDateTime.now(),
                settingsVersion = 1, trigger = ConsentTrigger.PARTICIPANT_TOGGLE, decisionSeq = 1))
            db.seedDecision(connection, study, device, key, CollectionAcknowledgment(emptySet(), OffsetDateTime.now(),
                setOf(module), settingsVersion = 1, trigger = ConsentTrigger.PARTICIPANT_TOGGLE, decisionSeq = 2))
            db.updateCollectionSettings(connection, study, required = true, settingsVersion = 2)
            db.seedDecision(connection, study, device, key, CollectionAcknowledgment(setOf(module), OffsetDateTime.now(),
                settingsVersion = 2, trigger = ConsentTrigger.PARTICIPANT_TOGGLE, decisionSeq = 3))
        }
        val outcomes = (1L..3L).map { seq ->
            guarded(study, device, setOf(module), if (seq == 3L) 2 else 1, seq, allowPartial = true) { it.acceptedModules to it.discardedModules }
        }
        assertEquals(listOf(setOf(module) to emptySet(), emptySet<CollectionModuleId>() to setOf(module),
            setOf(module) to emptySet()), outcomes)
    }

    /** The upload recheck as a device write calls it: every module must be accepted unless [allowPartial]. */
    private fun <T> guarded(studyId: UUID, device: UUID, modules: Set<CollectionModuleId> = emptySet(), version: Int? = null,
                            seq: Long? = null, allowPartial: Boolean = false,
                            write: (CollectionUploadAuthorization) -> T): T =
        service.withCollectionUploadRecheck(
            CollectionUploadRequest(studyId, PARTICIPANT_ID, device, version, seq), modules, allowPartial, write = write)

    /** A required-settings revision is one of the two ways a collection halt is recorded. */
    private fun recordHalt(studyId: UUID, settingsVersion: Int, device: UUID) {
        postgres.createConnection("").use { connection ->
            db.seedDecision(connection, studyId, device, UUID.randomUUID(), CollectionAcknowledgment(emptySet(),
                OffsetDateTime.now(), setOf(CollectionModuleId.BATTERY_TELEMETRY), settingsVersion = settingsVersion - 1))
            db.updateCollectionSettings(connection, studyId, required = true, settingsVersion = settingsVersion)
        }
    }

}
