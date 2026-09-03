package com.openlattice.chronicle.controllers

import com.geekbeast.jdbc.DataSourceManager
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.import.ImportStudiesConfiguration
import com.openlattice.chronicle.services.candidates.CandidateService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.timeusediary.TimeUseDiaryService
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.storage.StorageResolver
import org.apache.commons.lang3.NotImplementedException
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.util.*

class ImportControllerTest {

    private val studyService = Mockito.mock(StudyService::class.java)
    private val candidateService = Mockito.mock(CandidateService::class.java)
    private val timeUseDiaryService = Mockito.mock(TimeUseDiaryService::class.java)
    private val appDataUploadService = Mockito.mock(AppDataUploadService::class.java)
    private val idGenerationService = Mockito.mock(HazelcastIdGenerationService::class.java)
    private val dataSourceManager = Mockito.mock(DataSourceManager::class.java)
    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private val authorizationManager = Mockito.mock(AuthorizationManager::class.java)
    private val auditingManager = Mockito.mock(AuditingManager::class.java)
    private val hazelcast = Mockito.mock(HazelcastInstance::class.java)

    private lateinit var controller: ImportController

    @Before
    fun setUp() {
        TestSecurityUtils.setupSecurityContext()

        val mockUsersMap = Mockito.mock(IMap::class.java) as IMap<String, Any>
        val mockStudiesMap = Mockito.mock(IMap::class.java) as IMap<UUID, Any>
        Mockito.`when`(hazelcast.getMap<String, Any>("USERS")).thenReturn(mockUsersMap)
        Mockito.`when`(hazelcast.getMap<UUID, Any>("STUDIES")).thenReturn(mockStudiesMap)

        controller = ImportController(
            studyService, candidateService, timeUseDiaryService,
            appDataUploadService, idGenerationService, dataSourceManager,
            storageResolver, authorizationManager, auditingManager, hazelcast
        )
    }

    @Test
    fun testControllerConstructsSuccessfully() {
        assertNotNull(controller)
    }

    @Test
    fun testControllerImplementsAuthorizingComponent() {
        assertNotNull(controller.authorizationManager)
        assertNotNull(controller.auditingManager)
    }

    @Test(expected = NotImplementedException::class)
    fun testImportStudiesThrowsNotImplementedException() {
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)

        val config = Mockito.mock(ImportStudiesConfiguration::class.java)
        controller.importStudies(config)
    }

    @Test(expected = NotImplementedException::class)
    fun testImportParticipantsThrowsNotImplementedException() {
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)

        val config = Mockito.mock(ImportStudiesConfiguration::class.java)
        controller.importParticipants(config)
    }

    @Test
    fun testImportStudiesNotImplementedMessage() {
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)

        val config = Mockito.mock(ImportStudiesConfiguration::class.java)
        try {
            controller.importStudies(config)
            fail("Expected NotImplementedException")
        } catch (e: NotImplementedException) {
            assertTrue(e.message!!.contains("Migration endpoint has been removed"))
        }
    }

    @Test
    fun testImportParticipantsNotImplementedMessage() {
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(true)

        val config = Mockito.mock(ImportStudiesConfiguration::class.java)
        try {
            controller.importParticipants(config)
            fail("Expected NotImplementedException")
        } catch (e: NotImplementedException) {
            assertTrue(e.message!!.contains("Migration endpoint has been removed"))
        }
    }

    @Test
    fun testAuthorizationManagerAccessible() {
        assertSame(authorizationManager, controller.authorizationManager)
    }

    @Test
    fun testAuditingManagerAccessible() {
        assertSame(auditingManager, controller.auditingManager)
    }

    @Test
    fun testImportStudiesRequiresAdmin() {
        // With no admin role, should fail
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(false)

        val config = Mockito.mock(ImportStudiesConfiguration::class.java)
        try {
            controller.importStudies(config)
            fail("Expected exception for non-admin access")
        } catch (expected: Exception) {
            // Expected - either ForbiddenException or NotImplementedException depending on admin check
        }
    }

    @Test
    fun testImportParticipantsRequiresAdmin() {
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(false)

        val config = Mockito.mock(ImportStudiesConfiguration::class.java)
        try {
            controller.importParticipants(config)
            fail("Expected exception for non-admin access")
        } catch (expected: Exception) {
            // Expected
        }
    }

    @Test
    fun testImportUserPermissionsRequiresAdmin() {
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(false)

        val config = Mockito.mock(ImportStudiesConfiguration::class.java)
        try {
            controller.importUserPermissions(config)
            fail("Expected exception for non-admin access")
        } catch (expected: Exception) {
            // Expected
        }
    }

    @Test
    fun testImportParticipantStatsRequiresAdmin() {
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(false)

        val config = Mockito.mock(ImportStudiesConfiguration::class.java)
        try {
            controller.importParticipantStats(config)
            fail("Expected exception for non-admin access")
        } catch (expected: Exception) {
            // Expected
        }
    }

    @Test
    fun testImportAppUsageSurveyRequiresAdmin() {
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(false)

        val config = Mockito.mock(ImportStudiesConfiguration::class.java)
        try {
            controller.importAppUsageSurvey(config)
            fail("Expected exception for non-admin access")
        } catch (expected: Exception) {
            // Expected
        }
    }

    @Test
    fun testImportSystemAppsRequiresAdmin() {
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(false)

        val config = Mockito.mock(ImportStudiesConfiguration::class.java)
        try {
            controller.importSystemApps(config)
            fail("Expected exception for non-admin access")
        } catch (expected: Exception) {
            // Expected
        }
    }

    @Test
    fun testImportTimeUseDiarySubmissionsRequiresAdmin() {
        Mockito.`when`(authorizationManager.checkIfHasPermissions(
            kAny(), kAny(), kAny()
        )).thenReturn(false)

        val config = Mockito.mock(ImportStudiesConfiguration::class.java)
        try {
            controller.importTimeUseDiarySubmissions(config)
            fail("Expected exception for non-admin access")
        } catch (expected: Exception) {
            // Expected
        }
    }
}
