package com.openlattice.chronicle.controllers

import com.geekbeast.controllers.exceptions.ForbiddenException
import com.openlattice.chronicle.authorization.RoleAssignment
import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.authorization.aspects.StudyAuthorizationAspect
import com.openlattice.chronicle.services.roles.RoleService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.util.*

class RoleControllerTest {

    private val roleService = Mockito.mock(RoleService::class.java)
    private val controller = RoleController(roleService)

    @Before
    fun setUp() {
        TestSecurityUtils.setupSecurityContext()
    }

    @Test
    fun testControllerConstructsSuccessfully() {
        assertNotNull(controller)
    }

    @Test
    fun testControllerAcceptsRoleService() {
        val svc = Mockito.mock(RoleService::class.java)
        val ctrl = RoleController(svc)
        assertNotNull(ctrl)
    }

    // --- assignRole ---

    @Test
    fun testAssignRoleDelegatesToService() {
        val studyId = UUID.randomUUID()
        val assignment = Mockito.mock(RoleAssignment::class.java)

        controller.assignRole(studyId, assignment)
        verify(roleService).assignRole(studyId, assignment)
    }

    @Test
    fun testAssignRolePassesCorrectStudyId() {
        val studyId = UUID.randomUUID()
        val assignment = Mockito.mock(RoleAssignment::class.java)

        controller.assignRole(studyId, assignment)
        verify(roleService).assignRole(studyId, assignment)
    }

    @Test(expected = RuntimeException::class)
    fun testAssignRolePropagatesServiceException() {
        val studyId = UUID.randomUUID()
        val assignment = Mockito.mock(RoleAssignment::class.java)
        Mockito.doThrow(RuntimeException("assign error")).`when`(roleService).assignRole(studyId, assignment)

        controller.assignRole(studyId, assignment)
    }

    @Test
    fun testAssignRoleForDifferentStudies() {
        val studyId1 = UUID.randomUUID()
        val studyId2 = UUID.randomUUID()
        val assignment = Mockito.mock(RoleAssignment::class.java)

        controller.assignRole(studyId1, assignment)
        controller.assignRole(studyId2, assignment)
        verify(roleService).assignRole(studyId1, assignment)
        verify(roleService).assignRole(studyId2, assignment)
    }

    @Test
    fun testAssignRoleServiceCalledOnce() {
        val studyId = UUID.randomUUID()
        val assignment = Mockito.mock(RoleAssignment::class.java)

        controller.assignRole(studyId, assignment)
        verify(roleService, Mockito.times(1)).assignRole(studyId, assignment)
    }

    @Test
    fun testAssignRoleAopProxyRequiresManagePermissionsForRouteStudy() {
        val authorizationService = Mockito.mock(StudyAuthorizationService::class.java)
        val securedController = securedController(authorizationService)
        val studyId = UUID.randomUUID()
        val assignment = Mockito.mock(RoleAssignment::class.java)

        securedController.assignRole(studyId, assignment)

        verify(authorizationService).requirePermission(
            studyId,
            StudyPermission.MANAGE_PERMISSIONS,
        )
        verify(roleService).assignRole(studyId, assignment)
    }

    @Test
    fun testAssignRoleAopProxyDoesNotInvokeServiceWhenAuthorizationFails() {
        val authorizationService = Mockito.mock(StudyAuthorizationService::class.java)
        val securedController = securedController(authorizationService)
        val studyId = UUID.randomUUID()
        val assignment = Mockito.mock(RoleAssignment::class.java)
        Mockito.doThrow(ForbiddenException("denied"))
            .`when`(authorizationService)
            .requirePermission(studyId, StudyPermission.MANAGE_PERMISSIONS)

        assertThrows(ForbiddenException::class.java) {
            securedController.assignRole(studyId, assignment)
        }

        verify(roleService, never()).assignRole(studyId, assignment)
    }

    // --- revokeRole ---

    @Test
    fun testRevokeRoleDelegatesToService() {
        val studyId = UUID.randomUUID()
        val assignment = Mockito.mock(RoleAssignment::class.java)

        controller.revokeRole(studyId, assignment)
        verify(roleService).revokeRole(studyId, assignment)
    }

    @Test
    fun testRevokeRolePassesCorrectArgs() {
        val studyId = UUID.randomUUID()
        val assignment = Mockito.mock(RoleAssignment::class.java)

        controller.revokeRole(studyId, assignment)
        verify(roleService).revokeRole(studyId, assignment)
    }

    @Test(expected = RuntimeException::class)
    fun testRevokeRolePropagatesServiceException() {
        val studyId = UUID.randomUUID()
        val assignment = Mockito.mock(RoleAssignment::class.java)
        Mockito.doThrow(RuntimeException("revoke error")).`when`(roleService).revokeRole(studyId, assignment)

        controller.revokeRole(studyId, assignment)
    }

    @Test
    fun testRevokeRoleServiceCalledOnce() {
        val studyId = UUID.randomUUID()
        val assignment = Mockito.mock(RoleAssignment::class.java)

        controller.revokeRole(studyId, assignment)
        verify(roleService, Mockito.times(1)).revokeRole(studyId, assignment)
    }

    // --- listRoleAssignments ---

    @Test
    fun testListRoleAssignmentsDelegatesToService() {
        val studyId = UUID.randomUUID()
        val assignments = listOf(Mockito.mock(RoleAssignment::class.java))
        Mockito.`when`(roleService.listRoleAssignments(studyId)).thenReturn(assignments)

        val result = controller.listRoleAssignments(studyId)
        assertNotNull(result)
        assertEquals(1, result.size)
        verify(roleService).listRoleAssignments(studyId)
    }

    @Test
    fun testListRoleAssignmentsReturnsEmptyList() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(roleService.listRoleAssignments(studyId)).thenReturn(emptyList())

        val result = controller.listRoleAssignments(studyId)
        assertTrue(result.isEmpty())
    }

    @Test
    fun testListRoleAssignmentsReturnsMultipleAssignments() {
        val studyId = UUID.randomUUID()
        val assignments = listOf(
            Mockito.mock(RoleAssignment::class.java),
            Mockito.mock(RoleAssignment::class.java),
            Mockito.mock(RoleAssignment::class.java)
        )
        Mockito.`when`(roleService.listRoleAssignments(studyId)).thenReturn(assignments)

        val result = controller.listRoleAssignments(studyId)
        assertEquals(3, result.size)
    }

    @Test
    fun testListRoleAssignmentsReturnsSameList() {
        val studyId = UUID.randomUUID()
        val assignments = listOf(Mockito.mock(RoleAssignment::class.java))
        Mockito.`when`(roleService.listRoleAssignments(studyId)).thenReturn(assignments)

        val result = controller.listRoleAssignments(studyId)
        assertSame(assignments, result)
    }

    @Test(expected = RuntimeException::class)
    fun testListRoleAssignmentsPropagatesException() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(roleService.listRoleAssignments(studyId))
            .thenThrow(RuntimeException("list error"))

        controller.listRoleAssignments(studyId)
    }

    @Test
    fun testListRoleAssignmentsServiceCalledOnce() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(roleService.listRoleAssignments(studyId)).thenReturn(emptyList())

        controller.listRoleAssignments(studyId)
        verify(roleService, Mockito.times(1)).listRoleAssignments(studyId)
    }

    @Test
    fun testNoOtherInteractionsAfterAssign() {
        val studyId = UUID.randomUUID()
        val assignment = Mockito.mock(RoleAssignment::class.java)

        controller.assignRole(studyId, assignment)
        verify(roleService).assignRole(studyId, assignment)
        Mockito.verifyNoMoreInteractions(roleService)
    }

    @Test
    fun testNoOtherInteractionsAfterRevoke() {
        val studyId = UUID.randomUUID()
        val assignment = Mockito.mock(RoleAssignment::class.java)

        controller.revokeRole(studyId, assignment)
        verify(roleService).revokeRole(studyId, assignment)
        Mockito.verifyNoMoreInteractions(roleService)
    }

    @Test
    fun testNoOtherInteractionsAfterList() {
        val studyId = UUID.randomUUID()
        Mockito.`when`(roleService.listRoleAssignments(studyId)).thenReturn(emptyList())

        controller.listRoleAssignments(studyId)
        verify(roleService).listRoleAssignments(studyId)
        Mockito.verifyNoMoreInteractions(roleService)
    }

    @Test
    fun testAssignThenListFlow() {
        val studyId = UUID.randomUUID()
        val assignment = Mockito.mock(RoleAssignment::class.java)
        Mockito.`when`(roleService.listRoleAssignments(studyId)).thenReturn(listOf(assignment))

        controller.assignRole(studyId, assignment)
        val result = controller.listRoleAssignments(studyId)
        assertEquals(1, result.size)
    }

    private fun securedController(
        authorizationService: StudyAuthorizationService,
    ): RoleController {
        val factory = AspectJProxyFactory(RoleController(roleService))
        factory.isProxyTargetClass = true
        factory.addAspect(StudyAuthorizationAspect(authorizationService))
        return factory.getProxy()
    }
}
