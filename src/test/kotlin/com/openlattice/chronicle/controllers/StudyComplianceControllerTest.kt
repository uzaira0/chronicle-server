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

    private val studyLimitsMgr = Mockito.mock(StudyLimitsManager::class.java)
    private val studyService = Mockito.mock(StudyService::class.java)
    private val studyComplianceManager = Mockito.mock(StudyComplianceManager::class.java)
    private val studyComplianceHazelcastTask = Mockito.mock(StudyComplianceHazelcastTask::class.java)
    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private val auditingManager = Mockito.mock(AuditingManager::class.java)
    private val authorizationManager = Mockito.mock(AuthorizationManager::class.java)
    private val controller = StudyComplianceController(
        studyLimitsMgr, studyService, studyComplianceManager,
        studyComplianceHazelcastTask, storageResolver, auditingManager, authorizationManager
    )

    @Test
    fun testControllerConstructsWithAllDependencies() {
        assertNotNull(controller)
    }

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
    fun testGetStudyComplianceViolationsDelegatesToManager() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)
        Mockito.`when`(studyService.isValidStudy(studyId)).thenReturn(true)
        Mockito.`when`(studyComplianceManager.getNonCompliantStudies(listOf(studyId)))
            .thenReturn(emptyMap())

        TestSecurityUtils.setupSecurityContext()

        val result = controller.getStudyComplianceViolations(studyId)
        assertNotNull(result)
        verify(studyComplianceManager).getNonCompliantStudies(listOf(studyId))
    }

    // triggerComplianceNotificationsForAllStudies and triggerStudyComplianceNotifications
    // require ensureAdminAccess() which depends on Principals static context
    // and use AuditedTransactionBuilder with real DB connections.
    // Cannot be unit-tested with simple mocks. Covered by integration tests.

}
