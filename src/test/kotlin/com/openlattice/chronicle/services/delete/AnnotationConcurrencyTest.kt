package com.openlattice.chronicle.services.delete

import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.controllers.StudyController
import com.openlattice.chronicle.EditRevisionApi
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.filters.ConcurrentEditFilter
import com.openlattice.chronicle.filters.assertFilterError
import com.openlattice.chronicle.services.studies.StudySnapshotRepository
import com.openlattice.chronicle.storage.DeletionStudyFence
import com.openlattice.chronicle.storage.StorageResolver
import jakarta.servlet.FilterChain
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows
import com.openlattice.chronicle.configuration.JacksonSecurityConfig
import com.fasterxml.jackson.databind.JsonMappingException
import org.mockito.Mockito
import com.openlattice.chronicle.study.ParticipantAnnotationsUpdate
import com.openlattice.chronicle.study.StudyApi
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.study.Study
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import com.geekbeast.controllers.exceptions.ForbiddenException
import com.openlattice.chronicle.audit.accessedPHI
import com.openlattice.chronicle.audit.recordingAuditService
import com.openlattice.chronicle.audit.success
import com.openlattice.chronicle.authorization.StudyPermission
import org.junit.Assert.assertArrayEquals
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class AnnotationConcurrencyTest : ServerSweepTestFixture() {
    private fun annotations(vararg fields: Pair<String, Any?>): ParticipantAnnotationsUpdate =
        JacksonSecurityConfig().secureObjectMapper().convertValue(mapOf(*fields), ParticipantAnnotationsUpdate::class.java)

    @Test fun malformedAnnotationValuesAreRejectedBeforeAnyWrite() {
        val mapper = JacksonSecurityConfig().secureObjectMapper()
        val study = UUID.randomUUID()
        seed(study, "strict-patch")
        pool("strict-annotations").use { hds ->
            TestSecurityUtils.setupSecurityContext()
            try {
                val service = studyService(resolver(hds))
                service.updateParticipantAnnotations(study, "strict-patch", annotations(
                    StudySnapshotRepository.PARTICIPANT_NOTES_KEY to "retained",
                    StudySnapshotRepository.PARTICIPANT_TAGS_KEY to listOf("retained")))
                val mvc = MockMvcBuilders.standaloneSetup(annotationController(resolver(hds), Mockito.mock(AuditService::class.java)))
                    .setMessageConverters(MappingJackson2HttpMessageConverter(mapper)).build()
                mvc.perform(patch("${StudyApi.BASE}/$study/participant/strict-patch/annotations").contextPath(StudyApi.SERVICE)
                    .contentType(MediaType.APPLICATION_JSON).content("""{"participantNotes":42}"""))
                    .andExpect(status().isBadRequest)
                assertParticipantAnnotations(hds, study, "retained", listOf("retained"))
                for (value in listOf("42", "42.5", "true", "[]", "{}")) {
                    assertThrows(JsonMappingException::class.java) {
                        val patch = mapper.readValue("""{"participantNotes":$value}""",
                            ParticipantAnnotationsUpdate::class.java)
                        service.updateParticipantAnnotations(study, "strict-patch", patch)
                    }
                    assertParticipantAnnotations(hds, study, "retained", listOf("retained"))
                }
                for (value in listOf("42", "42.5", "true", "\"urgent\"", "{}", "[42]", "[true]", "[null]")) {
                    assertThrows(JsonMappingException::class.java) {
                        val patch = mapper.readValue("""{"participantTags":$value}""",
                            ParticipantAnnotationsUpdate::class.java)
                        service.updateParticipantAnnotations(study, "strict-patch", patch)
                    }
                    assertParticipantAnnotations(hds, study, "retained", listOf("retained"))
                }
            } finally { TestSecurityUtils.clearSecurityContext() }
        }
    }
    /** GET keeps the status, headers, bytes, ETag and audit entry the filter wrote before the controller owned the read. */
    @Test fun participantAnnotationGetServesTheLockedSnapshotThroughTheController() {
        val studyId = UUID.randomUUID(); val participantId = "get-${UUID.randomUUID()}"
        seed(studyId, participantId)
        pool("annotation-get").use { hds ->
            TestSecurityUtils.setupSecurityContext()
            try {
                val storage = resolver(hds)
                studyService(storage).updateParticipantAnnotations(studyId, participantId, annotations(
                    StudySnapshotRepository.PARTICIPANT_NOTES_KEY to "<b>n\u00f6te</b>",
                    StudySnapshotRepository.PARTICIPANT_TAGS_KEY to listOf("a", "b")))
                val snapshot = lockedAnnotations(hds, studyId, participantId)
                val (audit, recorded) = recordingAuditService()
                fun mvc(authorization: StudyAuthorizationService) = MockMvcBuilders
                    .standaloneSetup(annotationController(storage, audit))
                    .addFilters<StandaloneMockMvcBuilder>(ConcurrentEditFilter(storage, authorization)).build()
                val allowed = mvc(Mockito.mock(StudyAuthorizationService::class.java))
                val path = "${StudyApi.BASE}/$studyId/participant/$participantId/annotations"

                val loaded = allowed.perform(get(path).contextPath(StudyApi.SERVICE)).andReturn().response
                assertEquals(200, loaded.status)
                assertEquals("application/json;charset=UTF-8", loaded.contentType)
                assertArrayEquals(snapshot.toByteArray(Charsets.UTF_8), loaded.contentAsByteArray)
                assertEquals(ConcurrentEditFilter.revision(snapshot), loaded.getHeader("ETag"))
                val entry = recorded.events.single()
                assertEquals(AuditEventType.PARTICIPANT_DATA_ACCESS, entry.eventType)
                assertEquals("ParticipantAnnotations", entry.description)
                assertEquals(studyId, entry.study)
                assertTrue(entry.success && entry.accessedPHI)
                assertEquals(listOf("participantId", "participantNotes", "participantTags"), entry.data["phiFields"])
                assertEquals(AuditService.participantReference(participantId), entry.data["participantId"])

                val revision = allowed.perform(head(path).contextPath(StudyApi.SERVICE)).andReturn().response
                assertEquals(204, revision.status)
                assertEquals(ConcurrentEditFilter.revision(snapshot), revision.getHeader("ETag"))
                assertEquals(0, revision.contentAsByteArray.size)

                val absent = allowed.perform(get("${StudyApi.BASE}/$studyId/participant/absent/annotations")
                    .contextPath(StudyApi.SERVICE)).andReturn().response
                assertEquals(404, absent.status)
                assertFilterError(absent, 404, "Resource not found")

                val denial = Mockito.mock(StudyAuthorizationService::class.java)
                Mockito.doThrow(ForbiddenException("denied")).`when`(denial).requirePermission(studyId, StudyPermission.READ_STUDY)
                val forbidden = mvc(denial).perform(get(path).contextPath(StudyApi.SERVICE)).andReturn().response
                assertEquals(403, forbidden.status)
                assertFilterError(forbidden, 403, "Access denied")
                assertEquals("HEAD, 404 and 403 add no audit rows", 1, recorded.events.size)
            } finally { TestSecurityUtils.clearSecurityContext() }
        }
    }

    private fun lockedAnnotations(hds: com.zaxxer.hikari.HikariDataSource, studyId: UUID, participantId: String): String =
        hds.connection.use { connection ->
            connection.autoCommit = false
            try {
                requireNotNull(StudySnapshotRepository(connection).loadLockedParticipantAnnotations(studyId, participantId))
            } finally { connection.rollback() }
        }

    @Test fun participantAnnotationFilterTakesDeletionFenceBeforeParticipantRow() {
        val studyId = UUID.randomUUID(); val participantId = "sweep-${UUID.randomUUID()}"
        seed(studyId, participantId)
        val snapshot = postgres.createConnection("").use { connection ->
            connection.autoCommit = false
            try {
                DeletionStudyFence.shared(connection, studyId)
                requireNotNull(StudySnapshotRepository(connection).loadLockedParticipantAnnotations(studyId, participantId))
            } finally { connection.rollback() }
        }
        queuedRace(studyId, SweepRaceRow.PARTICIPANT) { storage ->
            val audit = Mockito.mock(AuditService::class.java)
            val filter = ConcurrentEditFilter(storage, Mockito.mock(StudyAuthorizationService::class.java))
            val path = "/chronicle/v3/study/$studyId/participant/$participantId/annotations"
            val controller = annotationController(storage, audit)
            val request = MockHttpServletRequest("PATCH", path).apply {
                addHeader(EditRevisionApi.IF_MATCH, ConcurrentEditFilter.revision(snapshot))
            }
            val response = MockHttpServletResponse()
            filter.doFilter(request, response, FilterChain { _, _ ->
                controller.updateParticipantAnnotations(studyId, participantId,
                    annotations(StudySnapshotRepository.PARTICIPANT_NOTES_KEY to "saved through filter"),
                    requireNotNull(request.getHeader(EditRevisionApi.IF_MATCH)))
            })
            assertEquals(200, response.status)
        }
        postgres.createConnection("").use { connection ->
            connection.prepareStatement("SELECT participant_notes FROM study_participants WHERE study_id = ?").use {
                it.setObject(1, studyId)
                it.executeQuery().use { rows -> assertTrue(rows.next()); assertEquals("saved through filter", rows.getString(1)) }
            }
        }
    }

    @Test fun partialParticipantAnnotationsPreserveOmittedFieldsAndAllowExplicitClears() {
        val studyId = UUID.randomUUID(); val participantId = "sweep-${UUID.randomUUID()}"
        seed(studyId, participantId)
        pool("sweep-partial-annotations").use { hds ->
            TestSecurityUtils.setupSecurityContext()
            try {
                val service = studyService(resolver(hds))
                val notesKey = StudySnapshotRepository.PARTICIPANT_NOTES_KEY
                val tagsKey = StudySnapshotRepository.PARTICIPANT_TAGS_KEY
                fun assertAnnotations(notes: String?, tags: List<String>) = assertParticipantAnnotations(hds, studyId, notes, tags)
                service.updateParticipantAnnotations(studyId, participantId, annotations(notesKey to "original", tagsKey to listOf("tag")))
                service.updateParticipantAnnotations(studyId, participantId, annotations(notesKey to "edited"))
                assertAnnotations("edited", listOf("tag"))
                service.updateParticipantAnnotations(studyId, participantId, annotations(tagsKey to listOf("new")))
                assertAnnotations("edited", listOf("new"))
                service.updateParticipantAnnotations(studyId, participantId, ParticipantAnnotationsUpdate())
                assertAnnotations("edited", listOf("new"))
                service.updateParticipantAnnotations(studyId, participantId, annotations(notesKey to null))
                assertAnnotations(null, listOf("new"))
                service.updateParticipantAnnotations(studyId, participantId, annotations(tagsKey to emptyList<String>()))
                assertAnnotations(null, emptyList())
                service.updateParticipantAnnotations(studyId, participantId, annotations(notesKey to "retained", tagsKey to listOf("tag")))
                service.updateParticipantAnnotations(studyId, participantId, annotations(tagsKey to null))
                assertAnnotations("retained", emptyList())
            } finally { TestSecurityUtils.clearSecurityContext() }
        }
    }

    private fun annotationController(storage: StorageResolver, audit: AuditService): StudyController {
        val authorization = Mockito.mock(AuthorizationManager::class.java)
        Mockito.`when`(authorization.checkIfHasPermissions(any(), any(), any())).thenReturn(true)
        val hazelcast = mock<HazelcastInstance>()
        Mockito.`when`(hazelcast.getMap<UUID, Study>(HazelcastMap.STUDIES.name)).thenReturn(mock())
        return StudyController(
            hazelcastInstance = hazelcast,
            storageResolver = storage,
            enrollmentService = mock(),
            studyService = studyService(storage),
            screenTimeUsageUploadService = mock(),
            androidSensorDataUploadService = mock(),
            batteryTelemetryUploadService = mock(),
            interactionEventsUploadService = mock(),
            appAudioActivityUploadService = mock(),
            appAudioContentUploadService = mock(),
            notificationActivityUploadService = mock(),
            sleepEventsUploadService = mock(),
            activityRecognitionEventsUploadService = mock(),
            healthMetricsUploadService = mock(),
            connectivityStateEventsUploadService = mock(),
            appNetworkUsageUploadService = mock(),
            deviceSettingsUploadService = mock(),
            uploadDiagnosticsUploadService = mock(),
            appDataUploadService = mock(),
            downloadService = mock(),
            enrollmentManager = mock(),
            authorizationManager = authorization,
            auditingManager = mock(),
            auditService = audit,
            studySettingsAuditService = mock(),
            participantCollectionAcknowledgmentService = mock(),
            studySettingsNotificationService = mock(),
            apiKeyService = mock(),
            dataDeletionOrchestrator = mock(),
            studyLifecycleService = mock(),
            webhookService = mock(),
            enrollmentManifestService = mock(),
        )
    }
}
