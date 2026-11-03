package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.seedCandidate

import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.delete.RestoreContinuityFixture
import com.openlattice.chronicle.services.delete.RestoreContinuityFixture.Companion.storageResolver
import com.openlattice.chronicle.services.enrollment.EnrollmentAuthorization
import com.openlattice.chronicle.services.enrollment.EnrollmentManifestService
import com.openlattice.chronicle.services.enrollment.loadLockedStudy
import com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeIssuerType
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.util.tests.TestDataFactory
import com.openlattice.chronicle.util.tests.participantFormAccessServiceForTest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito

class CommittedEnrollmentReplayTest {
    private val studyFixture get() = MigrationStudyFixture(storageResolver)
    companion object {
        @BeforeClass @JvmStatic fun start() = RestoreContinuityFixture.start()
        @AfterClass @JvmStatic fun stop() = RestoreContinuityFixture.stop()
    }

    @Test
    fun testCommittedEnrollmentRetryUsesStoredManifestAfterStudyRename() = RLSRequestContext.withSystemContext {
        val studyId = UUID.randomUUID()
        val participantId = "enrollment-retry"
        val clock = Clock.fixed(Instant.now().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC)
        val accessService = participantFormAccessServiceForTest(storageResolver, clock = clock)
        val studyService = Mockito.mock(StudyService::class.java)
        Mockito.`when`(studyService.getStudy(studyId)).thenAnswer {
            storageResolver.getPlatformStorage().connection.use { loadLockedStudy(it, studyId) }
        }
        val publicOrigin = "https://research.example.org"
        val issuer = "enrollment-retry-test"
        val manifestService = EnrollmentManifestService(studyService, accessService, publicOrigin)
        val settings = StudySettings(mapOf(
            StudySettingType.ParticipantPolicy to TestDataFactory.participantPolicy(),
            StudySettingType.DataCollection to AndroidDataCollectionSetting(settingsVersion = 7),
        ))
        val attemptId = UUID.randomUUID().toString()
        val sourceDevice = TestDataFactory.androidDevice()
        val sourceRandomDeviceId = sourceDevice.randomDeviceId
        val proposedKey = "ck_01234567_0123456789ABCDEFGHIJKLMNOPQRSTUV" // gitleaks:allow -- deterministic test credential
        fun authorize(service: EnrollmentManifestService, code: String, digest: String, key: String = proposedKey) =
            service.authorizeEnrollmentAttempt(studyId, participantId, code, digest, attemptId, sourceRandomDeviceId, sourceDevice, key)
        fun serviceAfter(elapsed: Duration) = EnrollmentManifestService(
            studyService,
            participantFormAccessServiceForTest(
                storageResolver = storageResolver,
                clock = Clock.offset(clock, elapsed),
            ),
            publicOrigin,
        )
        studyFixture.insertStudySettings(studyId, "Original study title", settings, collectionEnabled = true)
        try {
            insertEnrolledParticipant(studyId, participantId)
            val code = accessService.createAccessCode(
                studyId, participantId, ParticipantFormKind.ENROLLMENT, null, null,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).plusHours(1),
                ParticipantAccessCodeIssuerType.RESEARCHER, issuer,
            ).accessCode
            val digest = manifestService.getPreview(studyId, participantId, code).manifestDigest
            assertEquals(EnrollmentAuthorization.AUTHORIZED, authorize(manifestService, code, digest))
            storageResolver.getPlatformStorage().connection.use { connection ->
                executeSingleUpdate(connection, "UPDATE studies SET title = ? WHERE study_id = ?") {
                    it.setString(1, "Renamed study title")
                    it.setObject(2, studyId)
                }
            }
            val freshCode = accessService.createAccessCode(
                studyId, participantId, ParticipantFormKind.ENROLLMENT, null, null, null,
                ParticipantAccessCodeIssuerType.RESEARCHER, issuer,
            ).accessCode
            assertEquals("first consumption must still validate the current manifest",
                EnrollmentAuthorization.STUDY_CHANGED, authorize(manifestService, freshCode, digest))
            val currentDigest = manifestService.getPreview(studyId, participantId, freshCode).manifestDigest
            assertNotEquals(digest, currentDigest)
            assertEquals("committed retry must outlive both a title change and the invitation expiry",
                EnrollmentAuthorization.AUTHORIZED, authorize(serviceAfter(Duration.ofHours(2)), code, digest))
            assertEquals(EnrollmentAuthorization.REFUSED, authorize(manifestService, code, currentDigest))
            assertEquals(EnrollmentAuthorization.REFUSED, authorize(manifestService, code, digest, key = proposedKey.dropLast(1) + "W"))
            assertEquals("receipt recovery deadline must remain strict",
                EnrollmentAuthorization.REFUSED, authorize(serviceAfter(Duration.ofHours(24)), code, digest))
        } finally {
            storageResolver.getPlatformStorage().connection.use { deleteById(it, "study_participants", "study_id", studyId) }
            studyFixture.deleteStudyRow(studyId)
        }
    }

    /**
     * A phone that already enrolled through one QR code retries the same attempt with a newer invitation the
     * researcher re-sent. The attempt id is already bound, so the second binding is refused (not a server error)
     * and the first enrollment's replay receipt survives the refused attempt.
     */
    @Test
    fun boundAttemptRetriedOnAReSentInvitationIsRefusedAndKeepsItsFirstBinding() = RLSRequestContext.withSystemContext {
        val studyId = UUID.randomUUID()
        val participantId = "enrollment-resent"
        val clock = Clock.fixed(Instant.now().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC)
        val accessService = participantFormAccessServiceForTest(storageResolver, clock = clock)
        val studyService = Mockito.mock(StudyService::class.java)
        Mockito.`when`(studyService.getStudy(studyId)).thenAnswer {
            storageResolver.getPlatformStorage().connection.use { loadLockedStudy(it, studyId) }
        }
        val manifestService = EnrollmentManifestService(studyService, accessService, "https://research.example.org")
        val attemptId = UUID.randomUUID().toString()
        val sourceDevice = TestDataFactory.androidDevice()
        val proposedKey = "ck_01234567_0123456789ABCDEFGHIJKLMNOPQRSTUV" // gitleaks:allow -- deterministic test credential
        fun invitation() = accessService.createAccessCode(
            studyId, participantId, ParticipantFormKind.ENROLLMENT, null, null, null,
            ParticipantAccessCodeIssuerType.RESEARCHER, "enrollment-resent-test",
        ).accessCode
        fun digest(code: String) = manifestService.getPreview(studyId, participantId, code).manifestDigest
        fun authorize(code: String, digest: String) = manifestService.authorizeEnrollmentAttempt(studyId, participantId,
            code, digest, attemptId, sourceDevice.randomDeviceId, sourceDevice, proposedKey)
        studyFixture.insertStudySettings(studyId, "Pilot study", StudySettings(mapOf(
            StudySettingType.ParticipantPolicy to TestDataFactory.participantPolicy(),
            StudySettingType.DataCollection to AndroidDataCollectionSetting(settingsVersion = 1),
        )), collectionEnabled = true)
        try {
            insertEnrolledParticipant(studyId, participantId)
            val first = invitation()
            val firstDigest = digest(first)
            assertEquals(EnrollmentAuthorization.AUTHORIZED, authorize(first, firstDigest))
            val resent = invitation()
            assertEquals(EnrollmentAuthorization.REFUSED, authorize(resent, digest(resent)))
            assertEquals("the refused retry must not revoke the first binding",
                EnrollmentAuthorization.AUTHORIZED, authorize(first, firstDigest))
        } finally {
            storageResolver.getPlatformStorage().connection.use { deleteById(it, "study_participants", "study_id", studyId) }
            studyFixture.deleteStudyRow(studyId)
        }
    }

    /** Final enrollment on a study whose row is gone answers a conflict the phone shows, not a server error. */
    @Test
    fun lockedStudyLoadForAStudyWithoutARowIsAConflict() = RLSRequestContext.withSystemContext {
        val error = storageResolver.getPlatformStorage().connection.use { connection ->
            org.junit.Assert.assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
                loadLockedStudy(connection, UUID.randomUUID())
            }
        }
        assertEquals(409, error.statusCode.value())
        assertEquals("Study enrollment configuration is unavailable", error.reason)
    }

    private fun insertEnrolledParticipant(studyId: UUID, participantId: String) = storageResolver.getPlatformStorage().connection.use { connection ->
        executeSingleUpdate(connection,
            """INSERT INTO study_participants (study_id, participant_id, candidate_id, participation_status)
                VALUES (?, ?, ?, ?)""".trimIndent()) {
            it.setObject(1, studyId)
            it.setString(2, participantId)
            it.setObject(3, seedCandidate(connection))
            it.setString(4, ParticipationStatus.ENROLLED.name)
        }
    }
}
