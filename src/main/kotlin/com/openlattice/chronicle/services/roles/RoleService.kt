package com.openlattice.chronicle.services.roles

import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditingComponent
import com.openlattice.chronicle.auditing.AuditingManager
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.ChronicleStudyRole
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.authorization.RoleAssignment
import com.openlattice.chronicle.authorization.ScopeType
import com.openlattice.chronicle.authorization.SecurableObjectType
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.util.LogSanitizer
import org.slf4j.LoggerFactory
import java.util.EnumSet
import java.util.UUID

/**
 * Compatibility facade for the study-role API.
 *
 * Chronicle's ACL is the sole authorization authority. The former implementation
 * wrote a disconnected role_assignments table that no runtime check consumed.
 * This service now binds every request to the route study and mutates the ACL
 * directly so API responses and enforcement cannot drift.
 */
public open class RoleService(
    private val authorizationManager: AuthorizationManager,
    override val auditingManager: AuditingManager,
    hazelcastInstance: HazelcastInstance,
) : AuditingComponent {
    private val aclMutationLocks: IMap<AclKey, SecurableObjectType> =
        HazelcastMap.SECURABLE_OBJECT_TYPES.getMap(hazelcastInstance)

    internal companion object {
        private val logger = LoggerFactory.getLogger(RoleService::class.java)
        private val ALL_PERMISSIONS: EnumSet<Permission> = EnumSet.allOf(Permission::class.java)
        private val READ_PERMISSIONS: EnumSet<Permission> = EnumSet.of(Permission.READ)
        private val MANAGE_PERMISSIONS: EnumSet<Permission> = EnumSet.of(Permission.READ, Permission.WRITE)
        private val ADMIN_PERMISSIONS: EnumSet<Permission> =
            EnumSet.of(Permission.READ, Permission.WRITE, Permission.OWNER)
    }

    public fun assignRole(studyId: UUID, assignment: RoleAssignment) {
        validateRouteBoundAssignment(studyId, assignment)
        val permissions = when (assignment.role) {
            ChronicleStudyRole.RESEARCHER,
            ChronicleStudyRole.ANALYST -> EnumSet.copyOf(READ_PERMISSIONS)

            ChronicleStudyRole.COORDINATOR -> EnumSet.copyOf(MANAGE_PERMISSIONS)

            ChronicleStudyRole.STUDY_ADMIN,
            ChronicleStudyRole.PI -> EnumSet.copyOf(ALL_PERMISSIONS)

            ChronicleStudyRole.AUDITOR -> throw IllegalArgumentException(
                "AUDITOR cannot be represented safely by the current study ACL"
            )

            ChronicleStudyRole.SYSTEM_ADMIN -> throw IllegalArgumentException(
                "SYSTEM_ADMIN cannot be granted through a study-scoped role"
            )
        }

        val principal = Principal(assignment.principalType, assignment.principalId)
        // Persist a truthful write-ahead intent while the ACL is locked. An
        // unavailable audit sink must never permit an unaudited ACL mutation.
        withAclMutationLock(studyId) {
            recordRoleAuditEvent(
                studyId = studyId,
                principal = principal,
                permissions = permissions,
                eventType = AuditEventType.SET_PERMISSION,
                description = "Study role assignment requested through RoleApi",
                assignedRole = assignment.role,
            )
            authorizationManager.setPermission(AclKey(studyId), principal, permissions)
        }
        logger.info(
            "Assigned study role {} to principalRef {} for studyRef {}",
            assignment.role,
            LogSanitizer.stableFingerprint(assignment.principalId, prefix = "principal"),
            LogSanitizer.stableFingerprint(studyId.toString(), prefix = "study")
        )
    }

    public fun revokeRole(studyId: UUID, assignment: RoleAssignment) {
        validateRouteBoundScopeAndPrincipal(studyId, assignment)
        val principal = Principal(assignment.principalType, assignment.principalId)
        // ACL failures can leave this intent behind, so its wording deliberately
        // reports the requested operation rather than claiming it succeeded.
        withAclMutationLock(studyId) {
            recordRoleAuditEvent(
                studyId = studyId,
                principal = principal,
                permissions = ALL_PERMISSIONS,
                eventType = AuditEventType.REMOVE_PERMISSION,
                description = "Study role revocation requested through RoleApi",
            )
            authorizationManager.removePermission(
                AclKey(studyId),
                principal,
                EnumSet.copyOf(ALL_PERMISSIONS)
            )
        }
        logger.info(
            "Revoked study role from principalRef {} for studyRef {}",
            LogSanitizer.stableFingerprint(assignment.principalId, prefix = "principal"),
            LogSanitizer.stableFingerprint(studyId.toString(), prefix = "study")
        )
    }

    public fun listRoleAssignments(studyId: UUID): List<RoleAssignment> {
        return authorizationManager
            .getAllSecurableObjectPermissions(AclKey(studyId))
            .aces
            .filter { it.principal.type == PrincipalType.USER }
            .mapNotNull { ace ->
                val role = roleForPermissions(ace.permissions) ?: return@mapNotNull null
                RoleAssignment(
                    principalId = ace.principal.id,
                    principalType = ace.principal.type,
                    scopeType = ScopeType.STUDY,
                    scopeId = studyId,
                    role = role,
                )
            }
            .sortedWith(compareBy<RoleAssignment> { it.principalType.name }.thenBy { it.principalId })
    }

    public fun getRoleForPrincipal(
        principalId: String,
        scopeType: ScopeType,
        scopeId: UUID
    ): ChronicleStudyRole? {
        require(scopeType == ScopeType.STUDY) {
            "Only STUDY role scopes are supported by the study ACL"
        }
        return authorizationManager
            .getAllSecurableObjectPermissions(AclKey(scopeId))
            .aces
            .firstOrNull {
                it.principal.type == PrincipalType.USER && it.principal.id == principalId
            }
            ?.let { roleForPermissions(it.permissions) }
    }

    private fun validateRouteBoundAssignment(studyId: UUID, assignment: RoleAssignment) {
        validateRouteBoundScopeAndPrincipal(studyId, assignment)
        require(assignment.role != ChronicleStudyRole.SYSTEM_ADMIN) {
            "SYSTEM_ADMIN cannot be granted through a study-scoped role"
        }
    }

    private fun validateRouteBoundScopeAndPrincipal(studyId: UUID, assignment: RoleAssignment) {
        require(assignment.scopeType == ScopeType.STUDY) {
            "Study role requests must use STUDY scope"
        }
        require(assignment.scopeId == studyId) {
            "Role assignment scope must match the route study"
        }
        require(assignment.principalType == PrincipalType.USER) {
            "Only USER principals are supported by the study role API"
        }
    }

    private fun roleForPermissions(permissions: Set<Permission>): ChronicleStudyRole? {
        return when {
            permissions.containsAll(ADMIN_PERMISSIONS) -> ChronicleStudyRole.STUDY_ADMIN
            permissions.containsAll(MANAGE_PERMISSIONS) -> ChronicleStudyRole.COORDINATOR
            permissions.containsAll(READ_PERMISSIONS) -> ChronicleStudyRole.RESEARCHER
            else -> null
        }
    }

    private inline fun <T> withAclMutationLock(studyId: UUID, mutation: () -> T): T {
        val aclKey = AclKey(studyId)
        aclMutationLocks.lock(aclKey)
        return try {
            mutation()
        } finally {
            aclMutationLocks.unlock(aclKey)
        }
    }

    private fun recordRoleAuditEvent(
        studyId: UUID,
        principal: Principal,
        permissions: Set<Permission>,
        eventType: AuditEventType,
        description: String,
        assignedRole: ChronicleStudyRole? = null,
    ) {
        val data = buildMap<String, Any> {
            put("targetPrincipal", principal)
            put("permissions", permissions.map { it.name }.sorted())
            assignedRole?.let { put("assignedRole", it.name) }
        }
        check(
            recordEvent(
                AuditableEvent(
                    aclKey = AclKey(studyId),
                    eventType = eventType,
                    description = description,
                    study = studyId,
                    data = data,
                )
            ) == 1
        ) {
            "The requested role ACL mutation was not durably recorded before execution"
        }
    }
}
