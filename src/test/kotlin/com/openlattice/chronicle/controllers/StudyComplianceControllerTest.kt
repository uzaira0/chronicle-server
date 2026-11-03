package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.services.studies.StudyLimitsManager
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.studies.tasks.StudyComplianceHazelcastTask
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.StudyComplianceApi
import com.openlattice.chronicle.study.StudyComplianceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.springframework.web.bind.annotation.RequestMapping
import java.util.*

class StudyComplianceControllerTest {
    @Test
    fun missingStudyIsNotFound() {
        Mockito.`when`(authorizationManager.checkIfHasPermissions(kAny(), kAny(), kAny())).thenReturn(true)
        TestSecurityUtils.setupSecurityContext()
        try {
            org.junit.Assert.assertThrows(com.openlattice.chronicle.exceptions.StudyNotFoundException::class.java) {
                controller.getStudyComplianceViolations(UUID.randomUUID())
            }
        } finally {
            TestSecurityUtils.clearSecurityContext()
        }
    }


    private val studyLimitsMgr = Mockito.mock(StudyLimitsManager::class.java)
    private val studyService = Mockito.mock(StudyService::class.java)
    private val studyComplianceManager = Mockito.mock(StudyComplianceManager::class.java)
    private val studyComplianceHazelcastTask = Mockito.mock(StudyComplianceHazelcastTask::class.java)
    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private val auditingManager = Mockito.mock(AuditingManager::class.java)
    private val authorizationManager = Mockito.mock(AuthorizationManager::class.java)
    private val audit = com.openlattice.chronicle.audit.RecordingAuditingManager()
    private val auditService = com.openlattice.chronicle.audit.AuditService(audit)
    private val controller = StudyComplianceController(
        studyLimitsMgr, studyService, studyComplianceManager,
        studyComplianceHazelcastTask, storageResolver, auditingManager, authorizationManager, auditService
    )

    

    /**
     * The researcher dashboard fetches /chronicle/api/web/compliance/study/{id}, and both
     * reverse proxies rewrite that prefix to /chronicle/v3/. With only the unversioned
     * "/compliance" mapping the rewritten request hit no handler and the Compliance tab
     * returned 404 for every study. Both forms must stay mapped.
     */
    @Test
    fun testControllerIsMappedOnBothTheV3AndUnversionedPaths() {
        val mapping = StudyComplianceController::class.java
            .getAnnotation(RequestMapping::class.java)
        assertNotNull(mapping)
        val paths = mapping.value.toSet()
        assertTrue("missing unversioned mapping: $paths", paths.contains("/compliance"))
        assertTrue("missing dashboard-facing mapping: $paths", paths.contains("/v3/compliance"))
        assertEquals("/v3/compliance", StudyComplianceApi.V3_CONTROLLER)
    }

    @Test
    fun testGetStudyComplianceViolationsAuditsSuccessfulParticipantDataAccess() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)
        Mockito.`when`(studyService.getStudyId(studyId)).thenReturn(studyId)
        Mockito.`when`(studyComplianceManager.getStudyCompliancePage(studyId, 100, 0))
            .thenReturn(com.openlattice.chronicle.study.StudyCompliancePage(emptyMap(), null))

        TestSecurityUtils.setupSecurityContext()

        val result = controller.getStudyComplianceViolations(studyId)
        assertNotNull(result)
        verify(studyComplianceManager).getStudyCompliancePage(studyId, 100, 0)
        val event = audit.events.single()
        assertEquals(com.openlattice.chronicle.auditing.AuditEventType.PARTICIPANT_DATA_ACCESS, event.eventType)
        assertEquals(studyId, event.study)
        assertEquals(true, event.data["success"])
        assertEquals(true, event.data["accessedPHI"])
        TestSecurityUtils.clearSecurityContext()
    }

    // triggerComplianceNotificationsForAllStudies and triggerStudyComplianceNotifications
    // require ensureAdminAccess() which depends on Principals static context
    // and use AuditedTransactionBuilder with real DB connections.
    // Cannot be unit-tested with simple mocks. Covered by integration tests.


    @Test fun bothManualTriggersPropagatePersistenceFailureWithoutSuccessAudit() {
        TestSecurityUtils.setupSecurityContext()
        val study = UUID.randomUUID()
        val violations = mapOf(study to emptyMap<String, List<com.openlattice.chronicle.study.ComplianceViolation>>())
        val source = org.mockito.kotlin.mock<com.zaxxer.hikari.HikariDataSource>()
        val connection = org.mockito.kotlin.mock<java.sql.Connection>()
        Mockito.`when`(storageResolver.getPlatformStorage()).thenReturn(source)
        Mockito.`when`(source.connection).thenReturn(connection)
        Mockito.`when`(studyComplianceManager.getNonCompliantStudies(setOf(study))).thenReturn(violations)
        Mockito.`when`(studyComplianceManager.getAllNonCompliantStudies()).thenReturn(violations)
        Mockito.doAnswer { throw java.sql.SQLException("insert failed") }
            .`when`(studyComplianceHazelcastTask).notifyNonCompliantStudies(violations)
        org.junit.Assert.assertThrows(java.sql.SQLException::class.java) { controller.triggerStudyComplianceNotifications(setOf(study)) }
        org.junit.Assert.assertThrows(java.sql.SQLException::class.java) { controller.triggerComplianceNotificationsForAllStudies() }
        Mockito.verify(auditingManager, Mockito.times(2)).transactionStorage
        Mockito.verifyNoMoreInteractions(auditingManager)
        Mockito.verify(connection, Mockito.times(2)).rollback()
        TestSecurityUtils.clearSecurityContext()
    }

}
