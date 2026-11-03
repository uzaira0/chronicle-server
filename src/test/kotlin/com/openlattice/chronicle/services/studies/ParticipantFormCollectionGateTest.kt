package com.openlattice.chronicle.services.studies

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.exceptions.CollectionUnavailableException
import com.openlattice.chronicle.exceptions.StudyNotFoundException
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeCommand
import com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeIssuerType
import com.openlattice.chronicle.services.participantaccess.SubmissionReceiptScope
import com.openlattice.chronicle.util.tests.participantFormAccessServiceForTest
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Real-PostgreSQL contracts for the participant form gate: form codes, sessions and submissions follow the consent halt.
 * The app-usage survey asks about enrolled days, so it outlives a halt like a pause (AppUsageSurveyAdmissionTest).
 */
class ParticipantFormCollectionGateTest {

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
            db = ConsentGatePostgres("chronicle_form_gate")
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            if (Companion::db.isInitialized) db.close()
        }
    }

    @Test fun requiredDeclineClosesExistingSurveySessionsAndSubmissionUntilAccepted() {
        com.openlattice.chronicle.controllers.TestSecurityUtils.setupSecurityContext()
        try {
            val study = db.newStudy(required = true, settingsVersion = 1)
            val device = UUID.randomUUID()
            val key = UUID.randomUUID()
            val module = CollectionModuleId.BATTERY_TELEMETRY
            val forms = participantFormAccessServiceForTest(storageResolver)
            val code = forms.createAccessCode(study, PARTICIPANT_ID,
                com.openlattice.chronicle.participantaccess.ParticipantFormKind.TIME_USE_DIARY, null, null, null,
                com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeIssuerType.RESEARCHER, "consent-test")
            val session = checkNotNull(forms.exchangeAccessCode(code.accessCode))
            val scope = checkNotNull(forms.resolveSession(session.rawSessionToken, session.response.csrfToken, true))
            postgres.createConnection("").use { connection ->
                db.seedDecision(connection, study, device, key, CollectionAcknowledgment(emptySet(), OffsetDateTime.now(),
                    setOf(module), settingsVersion = 1, trigger = ConsentTrigger.SETTINGS_CHANGE))
            }
            org.junit.Assert.assertNull(forms.resolveSession(session.rawSessionToken, session.response.csrfToken, true))
            val probe = UUID.randomUUID()
            val receipts = com.openlattice.chronicle.services.participantaccess.ParticipantFormSubmissionReceiptService(storageResolver, mapper)
            assertThrows(CollectionUnavailableException::class.java) {
                receipts.executeWithSubmissionId(SubmissionReceiptScope(scope, scope.formKind, "halted", UUID.randomUUID()),
                    emptyList<String>()) { db.insertProbe(probe, study); probe }
            }
            assertFalse(db.probeExists(probe))
            postgres.createConnection("").use { connection ->
                db.seedDecision(connection, study, device, key, CollectionAcknowledgment(setOf(module), OffsetDateTime.now(),
                    settingsVersion = 1, trigger = ConsentTrigger.SETTINGS_CHANGE))
            }
            org.junit.Assert.assertNotNull(forms.resolveSession(session.rawSessionToken, session.response.csrfToken, true))
        } finally {
            com.openlattice.chronicle.controllers.TestSecurityUtils.clearSecurityContext()
        }
    }

    @Test fun requiredDeclineStopsFormCodeIssuanceAndExchangeUntilAccepted() {
        com.openlattice.chronicle.controllers.TestSecurityUtils.setupSecurityContext()
        try {
            val study = db.newStudy(required = true, settingsVersion = 1, sharedDevices = true)
            val device = UUID.randomUUID()
            val key = UUID.randomUUID()
            val module = CollectionModuleId.BATTERY_TELEMETRY
            val forms = participantFormAccessServiceForTest(storageResolver)
            fun researcherCode(kind: ParticipantFormKind) = forms.createAccessCode(study, PARTICIPANT_ID, kind, null, null,
                null, ParticipantAccessCodeIssuerType.RESEARCHER, "consent-test")
            fun deviceCodes() = forms.createAccessCodes(listOf(ParticipantAccessCodeCommand(study, PARTICIPANT_ID,
                ParticipantFormKind.TIME_USE_DIARY, null, null, null, ParticipantAccessCodeIssuerType.DEVICE, "device:$key")))
            val issuedBeforeDecline = researcherCode(ParticipantFormKind.TIME_USE_DIARY)
            postgres.createConnection("").use { connection ->
                db.seedDecision(connection, study, device, key, CollectionAcknowledgment(emptySet(), OffsetDateTime.now(),
                    setOf(module), settingsVersion = 1, trigger = ConsentTrigger.SETTINGS_CHANGE))
            }
            assertThrows(CollectionUnavailableException::class.java) { researcherCode(ParticipantFormKind.TIME_USE_DIARY) }
            assertThrows(CollectionUnavailableException::class.java) { deviceCodes() }
            val exchangeError = assertThrows(ResponseStatusException::class.java) {
                forms.exchangeAccessCode(issuedBeforeDecline.accessCode)
            }
            assertEquals(409, exchangeError.statusCode.value())
            // Enrollment capabilities stay available: the halt governs post-enrollment collection only.
            researcherCode(ParticipantFormKind.ENROLLMENT)
            // A standing required decline is a withdrawal: no app-usage survey either.
            assertThrows(CollectionUnavailableException::class.java) { researcherCode(ParticipantFormKind.APP_USAGE) }

            postgres.createConnection("").use { connection ->
                db.seedDecision(connection, study, device, key, CollectionAcknowledgment(setOf(module), OffsetDateTime.now(),
                    settingsVersion = 1, trigger = ConsentTrigger.SETTINGS_CHANGE))
            }
            org.junit.Assert.assertNotNull(forms.exchangeAccessCode(issuedBeforeDecline.accessCode))
            org.junit.Assert.assertNotNull(forms.exchangeAccessCode(researcherCode(ParticipantFormKind.TIME_USE_DIARY).accessCode))
            assertEquals(1, deviceCodes().size)
            researcherCode(ParticipantFormKind.APP_USAGE)
        } finally {
            com.openlattice.chronicle.controllers.TestSecurityUtils.clearSecurityContext()
        }
    }

    /** A participant who re-enrolls is governed by their latest decision, whichever enrollment recorded it. */
    @Test fun participantFormGateFollowsTheLatestDecisionAcrossEnrollments() {
        com.openlattice.chronicle.controllers.TestSecurityUtils.setupSecurityContext()
        try {
            val module = CollectionModuleId.BATTERY_TELEMETRY
            for (declineFirst in listOf(true, false)) {
                val study = db.newStudy(required = true, settingsVersion = 1)
                val forms = participantFormAccessServiceForTest(storageResolver)
                val laterKey = UUID.randomUUID()
                listOf(declineFirst, !declineFirst).forEachIndexed { index, declined ->
                    postgres.createConnection("").use { connection ->
                        db.seedDecision(connection, study, UUID.randomUUID(), if (index == 1) laterKey else UUID.randomUUID(),
                            CollectionAcknowledgment(if (declined) emptySet() else setOf(module), OffsetDateTime.now(),
                                if (declined) setOf(module) else emptySet(), settingsVersion = 1,
                                trigger = if (declined) ConsentTrigger.SETTINGS_CHANGE else ConsentTrigger.ENROLLMENT))
                    }
                }
                fun researcherCode() = forms.createAccessCode(study, PARTICIPANT_ID, ParticipantFormKind.TIME_USE_DIARY, null,
                    null, null, ParticipantAccessCodeIssuerType.RESEARCHER, "consent-test")
                if (declineFirst) {
                    val session = checkNotNull(forms.exchangeAccessCode(researcherCode().accessCode))
                    org.junit.Assert.assertNotNull(forms.resolveSession(session.rawSessionToken, session.response.csrfToken, true))
                    assertEquals(1, forms.createAccessCodes(listOf(ParticipantAccessCodeCommand(study, PARTICIPANT_ID,
                        ParticipantFormKind.TIME_USE_DIARY, null, null, null, ParticipantAccessCodeIssuerType.DEVICE,
                        "device:$laterKey"))).size)
                } else {
                    assertThrows(CollectionUnavailableException::class.java) { researcherCode() }
                }
            }
        } finally {
            com.openlattice.chronicle.controllers.TestSecurityUtils.clearSecurityContext()
        }
    }

    /**
     * Code issuance locks the study row and then replaces prior code rows; code exchange locks its code row and then
     * passes the same gate. The probe row stands in for the code row, so the two lock orders meet exactly as in production.
     */
    @Test fun formGateLockCannotDeadlockIssuanceAgainstExchange() {
        val study = db.newStudy(required = false, settingsVersion = 1)
        val codeRow = UUID.randomUUID()
        db.insertProbe(codeRow, study)
        val exchangeHoldsCode = CountDownLatch(1)
        val issuanceHoldsStudy = CountDownLatch(1)
        fun transaction(work: (java.sql.Connection) -> Unit) = postgres.createConnection("").use { connection ->
            connection.autoCommit = false
            try { work(connection); connection.commit() } catch (exception: Exception) { connection.rollback(); throw exception }
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val issuance = executor.submit { transaction { connection ->
                assertTrue(exchangeHoldsCode.await(30, TimeUnit.SECONDS))
                com.openlattice.chronicle.storage.ParticipantCollectionGate(connection).requireActive(study, PARTICIPANT_ID)
                issuanceHoldsStudy.countDown()
                connection.prepareStatement("UPDATE guard_probe SET study_id = study_id WHERE id = ?").use {
                    it.setObject(1, codeRow); it.executeUpdate()
                }
            } }
            val exchange = executor.submit { transaction { connection ->
                connection.prepareStatement("SELECT 1 FROM guard_probe WHERE id = ? FOR UPDATE").use {
                    it.setObject(1, codeRow); it.executeQuery().close()
                }
                exchangeHoldsCode.countDown()
                assertTrue(issuanceHoldsStudy.await(30, TimeUnit.SECONDS))
                assertTrue(com.openlattice.chronicle.storage.ParticipantCollectionGate(connection).isActive(study, PARTICIPANT_ID))
            } }
            exchange.get(30, TimeUnit.SECONDS)
            issuance.get(30, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun archiveRejectsAnUploadAndAnExistingFormSubmission() {
        com.openlattice.chronicle.controllers.TestSecurityUtils.setupSecurityContext()
        try {
            val studyId = db.newStudy(required = false, settingsVersion = 1)
            val forms = participantFormAccessServiceForTest(storageResolver)
            val code = forms.createAccessCode(studyId, PARTICIPANT_ID,
                com.openlattice.chronicle.participantaccess.ParticipantFormKind.TIME_USE_DIARY, null, null, null,
                com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeIssuerType.RESEARCHER, "archive-test")
            val session = checkNotNull(forms.exchangeAccessCode(code.accessCode))
            val scope = checkNotNull(forms.resolveSession(session.rawSessionToken, session.response.csrfToken, true))
            val ids = Mockito.mock(com.openlattice.chronicle.ids.HazelcastIdGenerationService::class.java)
            Mockito.`when`(ids.getNextId()).thenReturn(UUID.randomUUID())
            StudyLifecycleService(storageResolver, Mockito.mock(StudyService::class.java),
                Mockito.mock(com.openlattice.chronicle.authorization.AuthorizationManager::class.java), ids,
                Mockito.mock(AuditingManager::class.java),
                Mockito.mock(com.openlattice.chronicle.services.delete.DataDeletionOrchestrator::class.java),
                Mockito.mock(com.openlattice.chronicle.services.webhooks.WebhookService::class.java),
                Mockito.mock(StudyLimitsManager::class.java))
                .archiveStudy(studyId, "archive-test", expectedRevision = 0)
            val probe = UUID.randomUUID()
            org.junit.Assert.assertThrows(CollectionHaltedException::class.java) {
                service.withCollectionUploadRecheck(CollectionUploadRequest(studyId, PARTICIPANT_ID, UUID.randomUUID(), null, null), emptySet(),
                    allowPartial = false, allowQueuedCaptures = false) { db.insertProbe(probe, studyId) }
            }
            val receipts = com.openlattice.chronicle.services.participantaccess.ParticipantFormSubmissionReceiptService(storageResolver, mapper)
            val error = assertThrows(CollectionUnavailableException::class.java) {
                receipts.executeWithSubmissionId(
                    SubmissionReceiptScope(scope, scope.formKind, "archived", UUID.randomUUID()), emptyList<String>(),
                ) {
                    db.insertProbe(probe, studyId)
                    probe
                }
            }
            assertEquals(CollectionUnavailableException.Scope.PARTICIPANT, error.scope)
            assertFalse(db.probeExists(probe))
            org.junit.Assert.assertNull(forms.resolveSession(session.rawSessionToken, session.response.csrfToken, true))
        } finally {
            com.openlattice.chronicle.controllers.TestSecurityUtils.clearSecurityContext()
        }
    }

    /**
     * The dashboard greys out the participant policy and withdrawal settings once anyone has enrolled, through either
     * an enrolled participant row or a registered phone; before that the researcher may still edit them.
     */
    @Test fun policyAndWithdrawalSettingsLockOnceAParticipantOrPhoneIsEnrolled() {
        val forms = participantFormAccessServiceForTest(storageResolver)
        val study = db.newStudy(required = false, settingsVersion = 1)
        val locked = listOf(StudySettingType.ParticipantPolicy, StudySettingType.Withdrawal)
        assertEquals(locked, forms.getSettingLocks(study))
        postgres.createConnection("").use { connection ->
            connection.prepareStatement(
                "UPDATE study_participants SET participation_status = 'NOT_ENROLLED' WHERE study_id = ?",
            ).use { it.setObject(1, study); assertEquals(1, it.executeUpdate()) }
            assertEquals(emptyList<StudySettingType>(), forms.getSettingLocks(study))
            connection.prepareStatement(
                "INSERT INTO devices (study_id, participant_id, random_device_id, device_type, source_device) " +
                    "VALUES (?, ?, ?, 'ANDROID', '{}'::jsonb)",
            ).use {
                it.setObject(1, study); it.setString(2, PARTICIPANT_ID); it.setObject(3, UUID.randomUUID())
                assertEquals(1, it.executeUpdate())
            }
        }
        assertEquals(locked, forms.getSettingLocks(study))
        assertThrows(StudyNotFoundException::class.java) { forms.getSettingLocks(UUID.randomUUID()) }
    }
}
