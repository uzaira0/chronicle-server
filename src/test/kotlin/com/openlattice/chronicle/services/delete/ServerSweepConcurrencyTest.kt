package com.openlattice.chronicle.services.delete

import com.geekbeast.rhizome.KotlinDelegatedStringSet
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.mapstores.apps.FilteredAppsMapstore
import com.openlattice.chronicle.services.ScheduledTasksManager
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.studies.CollectionUploadRequest
import com.openlattice.chronicle.services.studies.ParticipantCollectionAcknowledgmentService
import com.openlattice.chronicle.services.surveys.SurveysService
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.study.ParticipantAnnotationsUpdate
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.util.tests.participantFormAccessServiceForTest
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any

/** Queued writers reproduce the row/fence inversion against the production schema. */
class ServerSweepConcurrencyTest : ServerSweepTestFixture() {
    @Test fun collectionGuardTakesDeletionFenceBeforeStudyRow() {
        val studyId = UUID.randomUUID(); val participantId = "sweep-${UUID.randomUUID()}"
        seed(studyId, participantId)
        queuedRace(studyId, SweepRaceRow.STUDY) { storage ->
            val randomDeviceId = UUID.randomUUID()
            val inserted = ParticipantCollectionAcknowledgmentService(storage).withCollectionUploadRecheck(
                CollectionUploadRequest(studyId, participantId, randomDeviceId, null, null), emptySet(),
                allowPartial = false, allowQueuedCaptures = false,
            ) {
                PinnedPlatformConnection.resolve(storage.getPlatformStorage()).connection.use { connection ->
                    connection.createStatement().use { it.executeUpdate("""INSERT INTO android_sensor_data
                        (study_id, participant_id, sample_id, random_device_id, sensor_type, sample_timestamp, timezone, values)
                        VALUES ('$studyId', '$participantId', '${UUID.randomUUID()}', '$randomDeviceId', 'ACCELEROMETER', now(), 'UTC', '[1,2,3]'::jsonb)""") }
                }
            }
            assertEquals(1, inserted)
        }
    }

    @Test fun participantStatusTakesDeletionFenceBeforeParticipantRow() = participantRace(false)

    @Test fun participantAnnotationsTakeDeletionFenceBeforeParticipantRow() = participantRace(true)

    @Test fun accessCodeExchangeTakesDeletionFenceBeforeCodeRow() {
        val studyId = UUID.randomUUID(); val participantId = "sweep-${UUID.randomUUID()}"
        seed(studyId, participantId)
        val accessCodeId = UUID.randomUUID(); val rawCode = "a".repeat(48)
        postgres.createConnection("").use { connection ->
            connection.prepareStatement(
                "INSERT INTO participant_form_access_codes (access_code_id, token_hash, study_id, participant_id, form_kind, issuer_type, " +
                "issued_by, expires_at) VALUES (?, ?, ?, ?, 'PORTAL', 'RESEARCHER', 'sweep', now() + interval '1 day')").use {
                it.setObject(1, accessCodeId); it.setBytes(2, MessageDigest.getInstance("SHA-256").digest(rawCode.toByteArray()))
                it.setObject(3, studyId); it.setString(4, participantId); it.executeUpdate()
            }
        }
        queuedRace(studyId, SweepRaceRow.ACCESS_CODE, accessCodeId) { storage ->
            val forms = participantFormAccessServiceForTest(storage)
            assertNotNull(forms.exchangeAccessCode(rawCode))
        }
    }

    @Test fun transactionalParticipantAuditWritersUseTheSharedSanitizer() {
        val studyId = UUID.randomUUID(); val participantId = "sweep-${UUID.randomUUID()}"
        seed(studyId, participantId)
        val audit = Mockito.mock(AuditingManager::class.java)
        val events = mutableListOf<AuditableEvent>()
        Mockito.doAnswer { events += it.getArgument<List<AuditableEvent>>(1); 1 }
            .`when`(audit).recordEvents(any<Connection>(), any())
        pool("sweep-audit").use { hds ->
            TestSecurityUtils.setupSecurityContext()
            try {
                val service = studyService(resolver(hds), audit)
                service.updateParticipationStatus(studyId, participantId, ParticipationStatus.PAUSED, expectedRevision = 0)
                service.updateParticipantAnnotations(studyId, participantId, ParticipantAnnotationsUpdate())
            } finally { TestSecurityUtils.clearSecurityContext() }
        }
        assertEquals(2, events.size)
        events.forEach {
            assertFalse(it.description.contains(participantId))
            assertTrue(it.description.contains(LogSanitizer.stableFingerprint(participantId, "participant")))
        }
    }

    @Test fun filteredAppsReadAndReloadRespectDurableStudyErasure() {
        for (status in listOf("QUARANTINED", "COMPLETED")) {
            val studyId = UUID.randomUUID(); val participantId = "sweep-${UUID.randomUUID()}"
            seed(studyId, participantId)
            pool("sweep-filtered-apps").use { hds ->
                hds.connection.use { connection ->
                    connection.createStatement().use {
                        it.execute("INSERT INTO filtered_apps (study_id, app_package_name) VALUES ('$studyId', 'example.package')")
                        it.execute("""INSERT INTO data_deletion_operations
                            (operation_id, study_id, mode, status, requested_by, idempotency_key, registry_version)
                            VALUES ('${UUID.randomUUID()}', '$studyId', 'STUDY_ERASURE', '$status', 'sweep', '${UUID.randomUUID()}', 1)""")
                    }
                }
                val hazelcast = Mockito.mock(HazelcastInstance::class.java)
                @Suppress("UNCHECKED_CAST")
                val apps = Mockito.mock(IMap::class.java) as IMap<UUID, KotlinDelegatedStringSet>
                Mockito.`when`(hazelcast.getMap<UUID, KotlinDelegatedStringSet>(HazelcastMap.FILTERED_APPS.name)).thenReturn(apps)
                Mockito.`when`(apps[studyId]).thenReturn(KotlinDelegatedStringSet(setOf("example.package")))
                val service = SurveysService(hazelcast, resolver(hds), Mockito.mock(EnrollmentManager::class.java),
                    Mockito.mock(ScheduledTasksManager::class.java), Mockito.mock(AuditingManager::class.java),
                    Mockito.mock(HazelcastIdGenerationService::class.java))
                TestSecurityUtils.setupSecurityContext()
                try {
                    assertThrows(NoSuchElementException::class.java) { service.getAppsFilteredForStudyAppUsageSurvey(studyId) }
                    Mockito.verify(apps).evict(studyId)
                    assertNull(FilteredAppsMapstore(hds).load(studyId))
                } finally { TestSecurityUtils.clearSecurityContext() }
            }
        }
    }
}
