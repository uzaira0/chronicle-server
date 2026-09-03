package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.services.studies.StudyLimitsManager
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.StudyLimits
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import java.util.*

class StudyLimitsControllerTest {

    private val studyLimitsMgr = Mockito.mock(StudyLimitsManager::class.java)
    private val studyService = Mockito.mock(StudyService::class.java)
    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private val auditingManager = Mockito.mock(AuditingManager::class.java)
    private val authorizationManager = Mockito.mock(AuthorizationManager::class.java)
    private val controller = StudyLimitsController(studyLimitsMgr, studyService, storageResolver, auditingManager, authorizationManager)

    @Test
    fun testGetStudyLimitsReturnsServiceResult() {
        val studyId = UUID.randomUUID()
        val expected = StudyLimits()
        Mockito.`when`(studyService.isValidStudy(studyId)).thenReturn(true)
        Mockito.`when`(studyLimitsMgr.getStudyLimits(studyId)).thenReturn(expected)
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)

        TestSecurityUtils.setupSecurityContext()

        val result = controller.getStudyLimits(studyId)
        assertNotNull(result)
        verify(studyLimitsMgr).getStudyLimits(studyId)
    }

    @Test(expected = IllegalStateException::class)
    fun testGetStudyLimitsRejectsInvalidStudy() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(studyService.isValidStudy(studyId)).thenReturn(false)
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)

        TestSecurityUtils.setupSecurityContext()

        controller.getStudyLimits(studyId)
    }

    @Test
    fun testGetStudyLimitsVerifiesStudyValidity() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(studyService.isValidStudy(studyId)).thenReturn(true)
        Mockito.`when`(studyLimitsMgr.getStudyLimits(studyId)).thenReturn(StudyLimits())
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)

        TestSecurityUtils.setupSecurityContext()

        controller.getStudyLimits(studyId)
        verify(studyService).isValidStudy(studyId)
    }

    @Test
    fun testStudyLimitsConstructorAcceptsAllDependencies() {
        assertNotNull(controller)
    }

}
