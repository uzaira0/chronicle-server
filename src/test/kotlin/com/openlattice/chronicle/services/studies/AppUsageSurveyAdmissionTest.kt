package com.openlattice.chronicle.services.studies

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.controllers.MobileReminderController
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.exceptions.CollectionUnavailableException
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeIssuerType
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.services.participantaccess.ParticipantFormSubmissionReceiptService
import com.openlattice.chronicle.services.participantaccess.SubmissionReceiptScope
import com.openlattice.chronicle.util.tests.participantFormAccessServiceForTest
import com.geekbeast.rhizome.KotlinDelegatedStringSet
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.services.surveys.AppUsageDayRules
import com.openlattice.chronicle.services.surveys.SurveysService
import com.openlattice.chronicle.settings.AppUsageFrequency
import org.junit.AfterClass
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.OffsetDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.util.UUID

/**
 * The app-usage survey is for shared devices: only a study collecting user identification has one.
 * The app-usage survey admits a participant for the days they were enrolled, whatever their status now.
 * Erased, withdrawn, not-enrolled, required-module-declined and processing-blocked participants, and studies scheduled
 * for deletion, are refused: withdrawal, a required decline and Mark not enrolled are a hard stop.
 */
class AppUsageSurveyAdmissionTest {
    companion object {
        private lateinit var db: ConsentGatePostgres
        private const val PARTICIPANT_ID = ConsentGatePostgres.PARTICIPANT_ID
        private val mapper = ObjectMappers.getJsonMapper()

        @BeforeClass @JvmStatic fun setUp() { db = ConsentGatePostgres("chronicle_survey_admission") }

        @AfterClass @JvmStatic fun tearDown() { if (Companion::db.isInitialized) db.close() }
    }

    private fun sql(statement: String, vararg values: Any?) = db.postgres.createConnection("").use { connection ->
        connection.prepareStatement(statement).use { prepared ->
            values.forEachIndexed { index, value -> prepared.setObject(index + 1, value) }
            prepared.executeUpdate()
        }
    }

    private fun collection(sharedDevices: Boolean, settingsVersion: Int) = AndroidDataCollectionSetting(
        modules = linkedMapOf(
            CollectionModuleId.USER_IDENTIFICATION to CollectionModuleSetting(enabled = sharedDevices),
            CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = true, required = true),
        ),
        settingsVersion = settingsVersion,
    )

    /** An enrolled participant with a device key, so they have enrolled time to be surveyed about. */
    private fun enrolledStudy(sharedDevices: Boolean = true): Pair<UUID, UUID> {
        val study = db.newStudy(required = false, settingsVersion = 1, collection = collection(sharedDevices, 1))
        val key = UUID.randomUUID()
        sql("""
            INSERT INTO api_keys (key_id, study_id, key_hash, key_prefix, scope, created_by, expires_at, participant_id,
                random_device_id)
            VALUES (?, ?, ?, 'test', 'WRITE', 'admission', now() + interval '1 day', ?, ?)
        """.trimIndent(), key, study, key.toString(), PARTICIPANT_ID, UUID.randomUUID())
        return study to key
    }

    private fun surveys(): SurveysService {
        val hazelcast = mock<HazelcastInstance>()
        whenever(hazelcast.getMap<UUID, KotlinDelegatedStringSet>("FILTERED_APPS")).thenReturn(mock<IMap<UUID, KotlinDelegatedStringSet>>())
        return SurveysService(hazelcast, db.storageResolver, mock(), mock(), mock(), mock())
    }

    /** The device's reminder manifest, read through the real survey admission. */
    private fun reminderKinds(study: UUID): List<ParticipantFormKind> {
        val enrollment = mock<EnrollmentManager>()
        whenever(enrollment.getDeviceParticipationStatus(study, PARTICIPANT_ID)).thenReturn(ParticipationStatus.ENROLLED)
        val token = ApiKeyAuthenticationToken("apikey:test", UUID.randomUUID(), study, PARTICIPANT_ID, UUID.randomUUID(),
            ApiKeyScope.READ_ONLY, listOf(SimpleGrantedAuthority("ROLE_API_KEY")))
        return MobileReminderController(enrollment, mock(), surveys(), mock())
            .getReminderSchedule(study, PARTICIPANT_ID, token, MockHttpServletResponse()).forms.map { it.formKind }
    }

    /** Both doors of the survey: the reminder manifest and a survey code the participant opens. */
    private fun surveyOpen(forms: ParticipantFormAccessService, study: UUID): Pair<Boolean, Boolean> {
        val opened = runCatching { forms.exchangeAccessCode(code(forms, study, ParticipantFormKind.APP_USAGE).accessCode) }
        val days = surveys().getAppUsageDays(study, PARTICIPANT_ID, AppUsageDayRules(ZoneId.of("UTC"), 60, AppUsageFrequency.DAILY))
        assertEquals(opened.getOrNull() != null, days != null)
        return (ParticipantFormKind.APP_USAGE in reminderKinds(study)) to (opened.getOrNull() != null)
    }

    @Test fun surveyRunsOnlyWhileTheStudyCollectsUserIdentification() = asResearcher {
        val forms = participantFormAccessServiceForTest(db.storageResolver)
        assertEquals(false to false, surveyOpen(forms, enrolledStudy(sharedDevices = false).first))
        val (study, _) = enrolledStudy(sharedDevices = true)
        assertEquals(true to true, surveyOpen(forms, study))
        val opened = checkNotNull(forms.exchangeAccessCode(code(forms, study, ParticipantFormKind.APP_USAGE).accessCode))
        // Turned off mid-study, the survey closes, an open session with it; turned on again, it reopens.
        db.postgres.createConnection("").use { db.updateCollectionSettings(it, study, false, 2, collection = collection(false, 2)) }
        assertEquals(false to false, surveyOpen(forms, study))
        assertTrue(runCatching { forms.resolveSession(opened.rawSessionToken, opened.response.csrfToken, true) }
            .let { it.isFailure || it.getOrNull() == null })
        db.postgres.createConnection("").use { db.updateCollectionSettings(it, study, false, 3, collection = collection(true, 3)) }
        assertEquals(true to true, surveyOpen(forms, study))
    }

    private fun setStatus(study: UUID, status: String) =
        sql("UPDATE study_participants SET participation_status = ? WHERE study_id = ? AND participant_id = ?",
            status, study, PARTICIPANT_ID)

    private fun code(forms: ParticipantFormAccessService, study: UUID, kind: ParticipantFormKind) =
        forms.createAccessCode(study, PARTICIPANT_ID, kind, null, null, null, ParticipantAccessCodeIssuerType.RESEARCHER,
            "admission-test")

    private fun <T> asResearcher(block: () -> T): T {
        TestSecurityUtils.setupSecurityContext()
        try { return block() } finally { TestSecurityUtils.clearSecurityContext() }
    }

    @Test fun surveyOutlivesTheStopOfCollection() = asResearcher {
        for (stop in listOf<(UUID) -> Unit>(
            { setStatus(it, "PAUSED") },
            { setStatus(it, "COLLECTION_COMPLETED") },
            { sql("UPDATE studies SET lifecycle_status = 'ARCHIVED' WHERE study_id = ?", it) },
        )) {
            val (study, _) = enrolledStudy()
            val forms = participantFormAccessServiceForTest(db.storageResolver)
            val openedBefore = checkNotNull(forms.exchangeAccessCode(code(forms, study, ParticipantFormKind.APP_USAGE).accessCode))
            val diary = checkNotNull(forms.exchangeAccessCode(code(forms, study, ParticipantFormKind.TIME_USE_DIARY).accessCode))
            stop(study)
            assertNotNull(forms.resolveSession(openedBefore.rawSessionToken, openedBefore.response.csrfToken, true))
            val issuedAfter = code(forms, study, ParticipantFormKind.APP_USAGE)
            val openedAfter = checkNotNull(forms.exchangeAccessCode(issuedAfter.accessCode))
            assertNotNull(forms.resolveSession(openedAfter.rawSessionToken, openedAfter.response.csrfToken, true))
            // Every other form still stops with collection.
            assertNull(forms.resolveSession(diary.rawSessionToken, diary.response.csrfToken, true))
            assertThrows(CollectionUnavailableException::class.java) { code(forms, study, ParticipantFormKind.TIME_USE_DIARY) }
        }
    }

    @Test fun withdrawnNotEnrolledDeclinedErasedAndBlockedParticipantsAreRefused() = asResearcher {
        for (refuse in listOf<(UUID, UUID) -> Unit>(
            // Mark not enrolled, from the dashboard.
            { study, _ -> setStatus(study, "NOT_ENROLLED") },
            // A standing decline of a required module (survey-lane OwnerTodo 5).
            { study, key ->
                db.postgres.createConnection("").use {
                    db.seedDecision(it, study, UUID.randomUUID(), key, CollectionAcknowledgment(emptySet(),
                        OffsetDateTime.now(), setOf(CollectionModuleId.BATTERY_TELEMETRY), settingsVersion = 1,
                        trigger = ConsentTrigger.SETTINGS_CHANGE))
                }
            },
            { study, key ->
                sql("""
                    INSERT INTO mobile_withdrawal_requests (request_id, api_key_id, study_id, participant_id,
                        random_device_id, already_withdrawn)
                    VALUES (?, ?, ?, ?, ?, false)
                """.trimIndent(), UUID.randomUUID(), key, study, PARTICIPANT_ID, UUID.randomUUID())
                setStatus(study, "NOT_ENROLLED")
            },
            // Erasure hides the participant row, and completion deletes it.
            { study, _ -> sql("DELETE FROM study_participants WHERE study_id = ? AND participant_id = ?", study, PARTICIPANT_ID) },
            { study, _ ->
                sql("""
                    UPDATE study_participants SET processing_blocked_at = now(), processing_block_reason = 'objection',
                        processing_blocked_by = 'admission-test'
                    WHERE study_id = ? AND participant_id = ?
                """.trimIndent(), study, PARTICIPANT_ID)
            },
        )) {
            val (study, key) = enrolledStudy()
            val forms = participantFormAccessServiceForTest(db.storageResolver)
            val opened = checkNotNull(forms.exchangeAccessCode(code(forms, study, ParticipantFormKind.APP_USAGE).accessCode))
            val unused = code(forms, study, ParticipantFormKind.APP_USAGE)
            refuse(study, key)
            val resolved = runCatching { forms.resolveSession(opened.rawSessionToken, opened.response.csrfToken, true) }
            assertTrue(resolved.isFailure || resolved.getOrNull() == null)
            assertTrue(runCatching { forms.exchangeAccessCode(unused.accessCode) }.let { it.isFailure || it.getOrNull() == null })
        }
    }

    /** Before a device enrolls there is nothing to ask about: the survey opens with no days. */
    @Test fun neverEnrolledOnADeviceHasNoDays() = asResearcher {
        val study = db.newStudy(required = false, settingsVersion = 1, collection = collection(true, 1))
        val forms = participantFormAccessServiceForTest(db.storageResolver)
        assertNotNull(forms.exchangeAccessCode(code(forms, study, ParticipantFormKind.APP_USAGE).accessCode))
        val days = surveys()
            .getAppUsageDays(study, PARTICIPANT_ID, AppUsageDayRules(ZoneId.of("America/Santiago"), 60, AppUsageFrequency.DAILY))
        assertEquals(emptyList<Any>(), checkNotNull(days).days)
    }

    /** A committed submission replays after the stop instead of being refused by it. */
    @Test fun committedSubmissionReplaysAfterTheStop() = asResearcher {
        val (study, _) = enrolledStudy()
        val forms = participantFormAccessServiceForTest(db.storageResolver)
        val opened = checkNotNull(forms.exchangeAccessCode(code(forms, study, ParticipantFormKind.TIME_USE_DIARY).accessCode))
        val scope = checkNotNull(forms.resolveSession(opened.rawSessionToken, opened.response.csrfToken, true))
        val receipts = ParticipantFormSubmissionReceiptService(db.storageResolver, mapper)
        val receipt = SubmissionReceiptScope(scope, scope.formKind, "time-use-diary:2026-10-27", UUID.randomUUID())
        val probe = UUID.randomUUID()
        val first = receipts.executeWithSubmissionId(receipt, listOf("answer")) { db.insertProbe(probe, study); probe }
        setStatus(study, "PAUSED")
        val replay = receipts.executeWithSubmissionId(receipt, listOf("answer")) { error("a replay never writes") }
        assertTrue(replay.replayed)
        assertEquals(first.value, replay.value)
        assertThrows(CollectionUnavailableException::class.java) {
            receipts.executeWithSubmissionId(receipt.copy(resourceKey = "time-use-diary:2026-10-28"), listOf("answer")) {
                db.insertProbe(UUID.randomUUID(), study); UUID.randomUUID()
            }
        }
        Unit
    }
}
