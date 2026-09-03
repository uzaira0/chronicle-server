package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.organizations.OrganizationMember
import com.openlattice.chronicle.organizations.OrganizationQuotas
import com.openlattice.chronicle.services.organizations.OrganizationMemberService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import java.util.*

class OrganizationMemberControllerTest {

    private val memberService = Mockito.mock(OrganizationMemberService::class.java)
    private val controller = OrganizationMemberController(memberService)

    @Before
    fun setUp() {
        TestSecurityUtils.setupSecurityContext()
    }

    @Test
    fun testControllerConstructsSuccessfully() {
        assertNotNull(controller)
    }

    @Test
    fun testControllerAcceptsMemberService() {
        val svc = Mockito.mock(OrganizationMemberService::class.java)
        val ctrl = OrganizationMemberController(svc)
        assertNotNull(ctrl)
    }

    // --- addMember ---

    @Test
    fun testAddMemberDelegatesToService() {
        val orgId = UUID.randomUUID()
        val member = Mockito.mock(OrganizationMember::class.java)

        val result = controller.addMember(orgId, member)
        assertEquals(OK.ok, result)
        verify(memberService).addMember(orgId, member)
    }

    @Test
    fun testAddMemberReturnsOk() {
        val orgId = UUID.randomUUID()
        val member = Mockito.mock(OrganizationMember::class.java)

        val result = controller.addMember(orgId, member)
        assertSame(OK.ok, result)
    }

    @Test
    fun testAddMemberPassesCorrectOrgId() {
        val orgId = UUID.randomUUID()
        val member = Mockito.mock(OrganizationMember::class.java)

        controller.addMember(orgId, member)
        verify(memberService).addMember(orgId, member)
    }

    @Test(expected = RuntimeException::class)
    fun testAddMemberPropagatesServiceException() {
        val orgId = UUID.randomUUID()
        val member = Mockito.mock(OrganizationMember::class.java)
        Mockito.doThrow(RuntimeException("add failed")).`when`(memberService).addMember(orgId, member)

        controller.addMember(orgId, member)
    }

    @Test
    fun testAddMemberMultipleOrgs() {
        val orgId1 = UUID.randomUUID()
        val orgId2 = UUID.randomUUID()
        val member = Mockito.mock(OrganizationMember::class.java)

        assertEquals(OK.ok, controller.addMember(orgId1, member))
        assertEquals(OK.ok, controller.addMember(orgId2, member))
        verify(memberService).addMember(orgId1, member)
        verify(memberService).addMember(orgId2, member)
    }

    // --- listMembers ---

    @Test
    fun testListMembersDelegatesToService() {
        val orgId = UUID.randomUUID()
        val members = listOf(Mockito.mock(OrganizationMember::class.java))
        Mockito.`when`(memberService.listMembers(orgId)).thenReturn(members)

        val result = controller.listMembers(orgId)
        assertNotNull(result)
        assertEquals(1, result.size)
        verify(memberService).listMembers(orgId)
    }

    @Test
    fun testListMembersReturnsEmptyList() {
        val orgId = UUID.randomUUID()
        Mockito.`when`(memberService.listMembers(orgId)).thenReturn(emptyList())

        val result = controller.listMembers(orgId)
        assertTrue(result.isEmpty())
    }

    @Test
    fun testListMembersReturnsMultipleMembers() {
        val orgId = UUID.randomUUID()
        val members = listOf(
            Mockito.mock(OrganizationMember::class.java),
            Mockito.mock(OrganizationMember::class.java),
            Mockito.mock(OrganizationMember::class.java)
        )
        Mockito.`when`(memberService.listMembers(orgId)).thenReturn(members)

        val result = controller.listMembers(orgId)
        assertEquals(3, result.size)
    }

    @Test
    fun testListMembersReturnsSameListFromService() {
        val orgId = UUID.randomUUID()
        val members = listOf(Mockito.mock(OrganizationMember::class.java))
        Mockito.`when`(memberService.listMembers(orgId)).thenReturn(members)

        val result = controller.listMembers(orgId)
        assertSame(members, result)
    }

    @Test(expected = RuntimeException::class)
    fun testListMembersPropagatesServiceException() {
        val orgId = UUID.randomUUID()
        Mockito.`when`(memberService.listMembers(orgId)).thenThrow(RuntimeException("list failed"))

        controller.listMembers(orgId)
    }

    // --- removeMember ---

    @Test
    fun testRemoveMemberDelegatesToService() {
        val orgId = UUID.randomUUID()
        val userId = "user-123"

        val result = controller.removeMember(orgId, userId)
        assertEquals(OK.ok, result)
        verify(memberService).removeMember(orgId, userId)
    }

    @Test
    fun testRemoveMemberReturnsOk() {
        val orgId = UUID.randomUUID()
        val userId = "user-123"

        val result = controller.removeMember(orgId, userId)
        assertSame(OK.ok, result)
    }

    @Test
    fun testRemoveMemberPassesCorrectArgs() {
        val orgId = UUID.randomUUID()
        val userId = "user-456"

        controller.removeMember(orgId, userId)
        verify(memberService).removeMember(orgId, userId)
    }

    @Test(expected = RuntimeException::class)
    fun testRemoveMemberPropagatesServiceException() {
        val orgId = UUID.randomUUID()
        val userId = "user-123"
        Mockito.doThrow(RuntimeException("remove failed")).`when`(memberService).removeMember(orgId, userId)

        controller.removeMember(orgId, userId)
    }

    // --- getQuotas ---

    @Test
    fun testGetQuotasDelegatesToService() {
        val orgId = UUID.randomUUID()
        val quotas = Mockito.mock(OrganizationQuotas::class.java)
        Mockito.`when`(memberService.getQuotas(orgId)).thenReturn(quotas)

        val result = controller.getQuotas(orgId)
        assertNotNull(result)
        assertSame(quotas, result)
        verify(memberService).getQuotas(orgId)
    }

    @Test(expected = RuntimeException::class)
    fun testGetQuotasPropagatesServiceException() {
        val orgId = UUID.randomUUID()
        Mockito.`when`(memberService.getQuotas(orgId)).thenThrow(RuntimeException("quota error"))

        controller.getQuotas(orgId)
    }

    // --- updateQuotas ---

    @Test
    fun testUpdateQuotasDelegatesToService() {
        val orgId = UUID.randomUUID()
        val quotas = Mockito.mock(OrganizationQuotas::class.java)
        val updatedQuotas = Mockito.mock(OrganizationQuotas::class.java)
        Mockito.`when`(memberService.updateQuotas(orgId, quotas)).thenReturn(updatedQuotas)

        val result = controller.updateQuotas(orgId, quotas)
        assertNotNull(result)
        assertSame(updatedQuotas, result)
        verify(memberService).updateQuotas(orgId, quotas)
    }

    @Test(expected = RuntimeException::class)
    fun testUpdateQuotasPropagatesServiceException() {
        val orgId = UUID.randomUUID()
        val quotas = Mockito.mock(OrganizationQuotas::class.java)
        Mockito.`when`(memberService.updateQuotas(orgId, quotas)).thenThrow(RuntimeException("update error"))

        controller.updateQuotas(orgId, quotas)
    }

    @Test
    fun testUpdateQuotasReturnsUpdatedQuotas() {
        val orgId = UUID.randomUUID()
        val quotas = Mockito.mock(OrganizationQuotas::class.java)
        val updatedQuotas = Mockito.mock(OrganizationQuotas::class.java)
        Mockito.`when`(memberService.updateQuotas(orgId, quotas)).thenReturn(updatedQuotas)

        val result = controller.updateQuotas(orgId, quotas)
        assertSame(updatedQuotas, result)
    }

    @Test
    fun testServiceCalledOnceForAddMember() {
        val orgId = UUID.randomUUID()
        val member = Mockito.mock(OrganizationMember::class.java)

        controller.addMember(orgId, member)
        verify(memberService, Mockito.times(1)).addMember(orgId, member)
    }

    @Test
    fun testServiceCalledOnceForListMembers() {
        val orgId = UUID.randomUUID()
        Mockito.`when`(memberService.listMembers(orgId)).thenReturn(emptyList())

        controller.listMembers(orgId)
        verify(memberService, Mockito.times(1)).listMembers(orgId)
    }

    @Test
    fun testNoOtherInteractionsAfterAddMember() {
        val orgId = UUID.randomUUID()
        val member = Mockito.mock(OrganizationMember::class.java)

        controller.addMember(orgId, member)
        verify(memberService).addMember(orgId, member)
        Mockito.verifyNoMoreInteractions(memberService)
    }
}
