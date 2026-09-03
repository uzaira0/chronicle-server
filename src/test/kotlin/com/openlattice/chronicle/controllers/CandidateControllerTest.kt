package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.services.candidates.CandidateService
import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.Mockito
import java.lang.reflect.Field

class CandidateControllerTest {

    private val storageResolver = Mockito.mock(StorageResolver::class.java)
    private val auditingManager = Mockito.mock(AuditingManager::class.java)
    private val authorizationManager = Mockito.mock(AuthorizationManager::class.java)
    private val auditService = Mockito.mock(AuditService::class.java)
    private val candidateService = Mockito.mock(CandidateService::class.java)
    private val controller = CandidateController(
        storageResolver, auditingManager, authorizationManager, auditService
    ).also {
        // Inject the @Inject lateinit candidateService via reflection
        val field: Field = CandidateController::class.java.getDeclaredField("candidateService")
        field.isAccessible = true
        field.set(it, candidateService)
    }

    @Test
    fun testControllerConstructsSuccessfully() {
        assertNotNull(controller)
    }

}
