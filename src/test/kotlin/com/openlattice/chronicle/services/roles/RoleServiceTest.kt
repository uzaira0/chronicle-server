package com.openlattice.chronicle.services.roles

import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.Ace
import com.openlattice.chronicle.authorization.Acl
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.ChronicleStudyRole
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.authorization.RoleAssignment
import com.openlattice.chronicle.authorization.SecurableObjectType
import com.openlattice.chronicle.authorization.ScopeType
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import java.time.OffsetDateTime
import java.util.EnumSet
import java.util.UUID

class RoleServiceTest {

    private val authorizationManager = Mockito.mock(AuthorizationManager::class.java)
    private val auditingManager = Mockito.mock(AuditingManager::class.java)
    private val hazelcastInstance = Mockito.mock(HazelcastInstance::class.java)
    @Suppress("UNCHECKED_CAST")
    private val aclMutationLocks = Mockito.mock(IMap::class.java) as IMap<AclKey, SecurableObjectType>
    private lateinit var service: RoleService

    @Before
    fun setUp() {
        TestSecurityUtils.setupSecurityContext(subject = "role-assigner")
        `when`(authorizationManager.getAllSecurableObjectPermissions(any<AclKey>()))
            .thenAnswer { invocation -> Acl(invocation.getArgument(0), emptyList()) }
        `when`(auditingManager.recordEvents(any())).thenReturn(1)
        `when`(
            hazelcastInstance.getMap<AclKey, SecurableObjectType>(
                HazelcastMap.SECURABLE_OBJECT_TYPES.name
            )
        ).thenReturn(aclMutationLocks)
        service = RoleService(authorizationManager, auditingManager, hazelcastInstance)
    }

    @After
    fun tearDown() {
        TestSecurityUtils.clearSecurityContext()
    }

    @Test
    fun researcherAssignmentReplacesAclWithReadPermission() {
        val studyId = UUID.randomUUID()
        val assignment = assignment(studyId, ChronicleStudyRole.RESEARCHER)

        service.assignRole(studyId, assignment)

        verify(authorizationManager).setPermission(
            AclKey(studyId),
            Principal(PrincipalType.USER, assignment.principalId),
            EnumSet.of(Permission.READ)
        )
    }

    @Test
    fun coordinatorAssignmentUsesExactReadWritePermissions() {
        val studyId = UUID.randomUUID()
        val assignment = assignment(studyId, ChronicleStudyRole.COORDINATOR)

        service.assignRole(studyId, assignment)

        verify(authorizationManager).setPermission(
            AclKey(studyId),
            Principal(PrincipalType.USER, assignment.principalId),
            EnumSet.of(Permission.READ, Permission.WRITE)
        )
    }

    @Test
    fun ownerAssignmentUsesEveryAclPermission() {
        val studyId = UUID.randomUUID()
        val assignment = assignment(studyId, ChronicleStudyRole.PI)

        service.assignRole(studyId, assignment)

        verify(authorizationManager).setPermission(
            AclKey(studyId),
            Principal(PrincipalType.USER, assignment.principalId),
            EnumSet.allOf(Permission::class.java)
        )
    }

    @Test
    fun successfulAssignmentRecordsSetPermissionAuditEvent() {
        val studyId = UUID.randomUUID()
        val assignment = assignment(studyId, ChronicleStudyRole.COORDINATOR)

        service.assignRole(studyId, assignment)

        val event = capturedAuditEvent()
        assertEquals(AclKey(studyId), event.aclKey)
        assertEquals(studyId, event.study)
        assertEquals(AuditEventType.SET_PERMISSION, event.eventType)
        assertEquals("Study role assignment requested through RoleApi", event.description)
        assertEquals("role-assigner", event.principal.id)
        assertEquals(
            Principal(PrincipalType.USER, assignment.principalId),
            event.data.getValue("targetPrincipal"),
        )
        assertEquals(ChronicleStudyRole.COORDINATOR.name, event.data.getValue("assignedRole"))
        assertEquals(
            listOf(Permission.READ.name, Permission.WRITE.name),
            event.data.getValue("permissions"),
        )
    }

    @Test
    fun assignmentAclFailureLeavesRequestedAuditIntent() {
        val studyId = UUID.randomUUID()
        val assignment = assignment(studyId, ChronicleStudyRole.RESEARCHER)
        val principal = Principal(PrincipalType.USER, assignment.principalId)
        doThrow(IllegalStateException("last owner"))
            .`when`(authorizationManager)
            .setPermission(
                AclKey(studyId),
                principal,
                EnumSet.of(Permission.READ)
            )

        assertThrows(IllegalStateException::class.java) {
            service.assignRole(studyId, assignment)
        }

        val captor = argumentCaptor<List<AuditableEvent>>()
        val order = inOrder(aclMutationLocks, auditingManager, authorizationManager)
        order.verify(aclMutationLocks).lock(AclKey(studyId))
        order.verify(auditingManager).recordEvents(captor.capture())
        order.verify(authorizationManager).setPermission(
            AclKey(studyId),
            principal,
            EnumSet.of(Permission.READ)
        )
        order.verify(aclMutationLocks).unlock(AclKey(studyId))
        assertEquals(
            "Study role assignment requested through RoleApi",
            captor.firstValue.single().description,
        )
    }

    @Test
    fun routeAndBodyStudyMismatchIsRejectedBeforeAclMutation() {
        val routeStudyId = UUID.randomUUID()
        val assignment = assignment(UUID.randomUUID(), ChronicleStudyRole.RESEARCHER)

        assertThrows(IllegalArgumentException::class.java) {
            service.assignRole(routeStudyId, assignment)
        }

        verifyNoInteractions(authorizationManager)
    }

    @Test
    fun organizationScopeIsRejectedBeforeAclMutation() {
        val studyId = UUID.randomUUID()
        val assignment = assignment(
            studyId,
            ChronicleStudyRole.RESEARCHER,
            scopeType = ScopeType.ORGANIZATION
        )

        assertThrows(IllegalArgumentException::class.java) {
            service.assignRole(studyId, assignment)
        }

        verifyNoInteractions(authorizationManager)
    }

    @Test
    fun nonUserPrincipalIsRejectedBeforeAclMutation() {
        val studyId = UUID.randomUUID()
        val assignment = assignment(
            studyId,
            ChronicleStudyRole.RESEARCHER,
            principalType = PrincipalType.ROLE
        )

        assertThrows(IllegalArgumentException::class.java) {
            service.assignRole(studyId, assignment)
        }

        verifyNoInteractions(authorizationManager)
    }

    @Test
    fun systemAdminCannotBeGrantedThroughStudyRole() {
        val studyId = UUID.randomUUID()

        assertThrows(IllegalArgumentException::class.java) {
            service.assignRole(studyId, assignment(studyId, ChronicleStudyRole.SYSTEM_ADMIN))
        }

        verifyNoInteractions(authorizationManager)
    }

    @Test
    fun auditorIsRejectedBecauseAclCannotRepresentItSafely() {
        val studyId = UUID.randomUUID()

        assertThrows(IllegalArgumentException::class.java) {
            service.assignRole(studyId, assignment(studyId, ChronicleStudyRole.AUDITOR))
        }

        verifyNoInteractions(authorizationManager)
    }

    @Test
    fun revocationRemovesEveryAclPermission() {
        val studyId = UUID.randomUUID()
        val assignment = assignment(studyId, ChronicleStudyRole.RESEARCHER)

        service.revokeRole(studyId, assignment)

        verify(authorizationManager).removePermission(
            AclKey(studyId),
            Principal(PrincipalType.USER, assignment.principalId),
            EnumSet.allOf(Permission::class.java)
        )
    }

    @Test
    fun successfulRevocationRecordsRemovePermissionAuditEvent() {
        val studyId = UUID.randomUUID()
        val assignment = assignment(studyId, ChronicleStudyRole.RESEARCHER)

        service.revokeRole(studyId, assignment)

        val event = capturedAuditEvent()
        assertEquals(AclKey(studyId), event.aclKey)
        assertEquals(studyId, event.study)
        assertEquals(AuditEventType.REMOVE_PERMISSION, event.eventType)
        assertEquals("Study role revocation requested through RoleApi", event.description)
        assertEquals("role-assigner", event.principal.id)
        assertEquals(
            Principal(PrincipalType.USER, assignment.principalId),
            event.data.getValue("targetPrincipal"),
        )
        assertFalse(event.data.containsKey("assignedRole"))
        assertEquals(
            Permission.entries.map { it.name }.sorted(),
            event.data.getValue("permissions"),
        )
    }

    @Test
    fun revocationAclFailureLeavesRequestedAuditIntent() {
        val studyId = UUID.randomUUID()
        val assignment = assignment(studyId, ChronicleStudyRole.STUDY_ADMIN)
        val principal = Principal(PrincipalType.USER, assignment.principalId)
        doThrow(IllegalStateException("last owner"))
            .`when`(authorizationManager)
            .removePermission(
                AclKey(studyId),
                principal,
                EnumSet.allOf(Permission::class.java),
            )

        assertThrows(IllegalStateException::class.java) {
            service.revokeRole(studyId, assignment)
        }

        val captor = argumentCaptor<List<AuditableEvent>>()
        val order = inOrder(aclMutationLocks, auditingManager, authorizationManager)
        order.verify(aclMutationLocks).lock(AclKey(studyId))
        order.verify(auditingManager).recordEvents(captor.capture())
        order.verify(authorizationManager).removePermission(
            AclKey(studyId),
            principal,
            EnumSet.allOf(Permission::class.java),
        )
        order.verify(aclMutationLocks).unlock(AclKey(studyId))
        assertEquals(
            "Study role revocation requested through RoleApi",
            captor.firstValue.single().description,
        )
    }

    @Test
    fun roleListIsDerivedFromAclAndUsesCanonicalRoles() {
        val studyId = UUID.randomUUID()
        val researcher = Principal(PrincipalType.USER, "researcher")
        val coordinator = Principal(PrincipalType.USER, "coordinator")
        val owner = Principal(PrincipalType.USER, "owner")
        `when`(authorizationManager.getAllSecurableObjectPermissions(AclKey(studyId))).thenReturn(
            Acl(
                AclKey(studyId),
                listOf(
                    Ace(owner, EnumSet.allOf(Permission::class.java)),
                    Ace(researcher, EnumSet.of(Permission.READ)),
                    Ace(coordinator, EnumSet.of(Permission.READ, Permission.WRITE)),
                )
            )
        )

        val roles = service.listRoleAssignments(studyId).associate { it.principalId to it.role }

        assertEquals(ChronicleStudyRole.RESEARCHER, roles.getValue(researcher.id))
        assertEquals(ChronicleStudyRole.COORDINATOR, roles.getValue(coordinator.id))
        assertEquals(ChronicleStudyRole.STUDY_ADMIN, roles.getValue(owner.id))
    }

    @Test
    fun roleListClassifiesOnlyPermissionSetsThatContainEveryRolePrerequisite() {
        val studyId = UUID.randomUUID()
        val researcher = Principal(PrincipalType.USER, "researcher-superset")
        val coordinator = Principal(PrincipalType.USER, "coordinator-superset")
        val ownerOnly = Principal(PrincipalType.USER, "owner-only")
        val ownerSuperset = Principal(PrincipalType.USER, "owner-superset")
        val writeOnly = Principal(PrincipalType.USER, "write-only")
        `when`(authorizationManager.getAllSecurableObjectPermissions(AclKey(studyId))).thenReturn(
            Acl(
                AclKey(studyId),
                listOf(
                    Ace(ownerOnly, EnumSet.of(Permission.OWNER)),
                    Ace(
                        ownerSuperset,
                        EnumSet.of(Permission.OWNER, Permission.WRITE, Permission.READ),
                    ),
                    Ace(writeOnly, EnumSet.of(Permission.WRITE)),
                    Ace(
                        coordinator,
                        EnumSet.of(
                            Permission.MATERIALIZE,
                            Permission.LINK,
                            Permission.READ,
                            Permission.WRITE,
                            Permission.INTEGRATE,
                        )
                    ),
                    Ace(
                        researcher,
                        EnumSet.of(Permission.MATERIALIZE, Permission.LINK, Permission.READ),
                    ),
                )
            )
        )

        val roles = service.listRoleAssignments(studyId).associateBy { it.principalId }

        assertEquals(ChronicleStudyRole.RESEARCHER, roles.getValue(researcher.id).role)
        assertEquals(ChronicleStudyRole.COORDINATOR, roles.getValue(coordinator.id).role)
        assertEquals(ChronicleStudyRole.STUDY_ADMIN, roles.getValue(ownerSuperset.id).role)
        assertFalse(roles.containsKey(ownerOnly.id))
        assertFalse(roles.containsKey(writeOnly.id))
        roles.values.forEach {
            assertNull(it.assignedBy)
            assertNull(it.assignedAt)
        }
    }

    @Test
    fun ownerAndWriteAloneDoNotOverstateThePrincipalsRole() {
        val studyId = UUID.randomUUID()
        val ownerOnly = Principal(PrincipalType.USER, "owner-only")
        val writeOnly = Principal(PrincipalType.USER, "write-only")
        `when`(authorizationManager.getAllSecurableObjectPermissions(AclKey(studyId))).thenReturn(
            Acl(
                AclKey(studyId),
                listOf(
                    Ace(ownerOnly, EnumSet.of(Permission.OWNER)),
                    Ace(writeOnly, EnumSet.of(Permission.WRITE)),
                )
            )
        )

        assertNull(service.getRoleForPrincipal(ownerOnly.id, ScopeType.STUDY, studyId))
        assertNull(service.getRoleForPrincipal(writeOnly.id, ScopeType.STUDY, studyId))
    }

    @Test
    fun failedAssignmentAuditDoesNotMutateAcl() {
        val studyId = UUID.randomUUID()
        val assignment = assignment(studyId, ChronicleStudyRole.COORDINATOR)
        doThrow(IllegalStateException("audit unavailable"))
            .`when`(auditingManager)
            .recordEvents(any())

        assertThrows(IllegalStateException::class.java) {
            service.assignRole(studyId, assignment)
        }

        verifyNoInteractions(authorizationManager)
        val order = inOrder(aclMutationLocks, auditingManager)
        order.verify(aclMutationLocks).lock(AclKey(studyId))
        order.verify(auditingManager).recordEvents(any())
        order.verify(aclMutationLocks).unlock(AclKey(studyId))
    }

    @Test
    fun failedRevocationAuditDoesNotMutateAcl() {
        val studyId = UUID.randomUUID()
        val assignment = assignment(studyId, ChronicleStudyRole.STUDY_ADMIN)
        doThrow(IllegalStateException("audit unavailable"))
            .`when`(auditingManager)
            .recordEvents(any())

        assertThrows(IllegalStateException::class.java) {
            service.revokeRole(studyId, assignment)
        }

        verifyNoInteractions(authorizationManager)
        val order = inOrder(aclMutationLocks, auditingManager)
        order.verify(aclMutationLocks).lock(AclKey(studyId))
        order.verify(auditingManager).recordEvents(any())
        order.verify(aclMutationLocks).unlock(AclKey(studyId))
    }

    @Test
    fun zeroRowAuditResultDoesNotMutateAcl() {
        val studyId = UUID.randomUUID()
        val assignment = assignment(studyId, ChronicleStudyRole.RESEARCHER)
        `when`(auditingManager.recordEvents(any())).thenReturn(0)

        assertThrows(IllegalStateException::class.java) {
            service.assignRole(studyId, assignment)
        }

        verifyNoInteractions(authorizationManager)
        val order = inOrder(aclMutationLocks, auditingManager)
        order.verify(aclMutationLocks).lock(AclKey(studyId))
        order.verify(auditingManager).recordEvents(any())
        order.verify(aclMutationLocks).unlock(AclKey(studyId))
    }

    @Test
    fun readWithoutAclPermissionHasNoRole() {
        val studyId = UUID.randomUUID()
        val principal = Principal(PrincipalType.USER, "no-access")
        `when`(authorizationManager.getAllSecurableObjectPermissions(AclKey(studyId))).thenReturn(
            Acl(
                AclKey(studyId),
                listOf(Ace(principal, emptySet(), OffsetDateTime.MAX))
            )
        )

        assertNull(service.getRoleForPrincipal(principal.id, ScopeType.STUDY, studyId))
    }

    private fun capturedAuditEvent(): AuditableEvent {
        val captor = argumentCaptor<List<AuditableEvent>>()
        verify(auditingManager).recordEvents(captor.capture())
        return captor.firstValue.single()
    }

    private fun assignment(
        studyId: UUID,
        role: ChronicleStudyRole,
        scopeType: ScopeType = ScopeType.STUDY,
        principalType: PrincipalType = PrincipalType.USER,
    ): RoleAssignment {
        return RoleAssignment(
            principalId = "user-1",
            principalType = principalType,
            scopeType = scopeType,
            scopeId = studyId,
            role = role,
        )
    }
}
