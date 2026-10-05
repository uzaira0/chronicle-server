package com.openlattice.chronicle.authorization

import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.filters.ApiKeyAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.EnumSet
import java.util.UUID

/**
 * What a researcher (non-device) API key may do. ApiKeyAuthenticationFilter admits such a key only on the
 * study-scoped routes in ResearcherApiKeyRoutes; both authorization paths (the ACL checks of
 * [AuthorizingComponent] and [StudyAuthorizationService.requirePermission]) then honour its scope on its own
 * study and nothing else. Device keys are not researcher keys and keep their mobile-only checks.
 * A key never outranks the person behind it: [ceiling] caps its scope at creation and on every use.
 */
internal object ApiKeyStudyAuthority {

    /** The current researcher key, or null when the request is not authenticated by one. */
    fun current(): ApiKeyAuthenticationToken? =
        (SecurityContextHolder.getContext().authentication as? ApiKeyAuthenticationToken)
            ?.takeIf { it.participantId == null }

    fun covers(granted: ApiKeyScope, required: ApiKeyScope): Boolean = when (granted) {
        ApiKeyScope.ADMIN -> true
        ApiKeyScope.WRITE -> required != ApiKeyScope.ADMIN
        ApiKeyScope.READ_ONLY -> required == ApiKeyScope.READ_ONLY
    }

    private fun aclPermissions(scope: ApiKeyScope): EnumSet<Permission> = when (scope) {
        ApiKeyScope.READ_ONLY -> EnumSet.of(Permission.READ)
        ApiKeyScope.WRITE -> EnumSet.of(Permission.READ, Permission.WRITE)
        // OWNER only reaches the destructive routes the filter lets an ADMIN key call.
        ApiKeyScope.ADMIN -> EnumSet.of(Permission.READ, Permission.WRITE, Permission.OWNER)
    }

    /**
     * Highest scope [principals] may hold on [studyId]: the scope whose ACL permissions they hold themselves
     * (READ -> READ_ONLY, READ+WRITE -> WRITE, READ+WRITE+OWNER -> ADMIN; OWNER is what DELETE_DATA needs of
     * a person). A system admin holds every study right. Null when they hold no access to the study at all.
     */
    fun ceiling(authorizationManager: AuthorizationManager, studyId: UUID, principals: Set<Principal>): ApiKeyScope? =
        if (principals.contains(Principals.getAdminRole())) {
            ApiKeyScope.ADMIN
        } else {
            ApiKeyScope.values().lastOrNull {
                authorizationManager.checkIfHasPermissions(AclKey(studyId), principals, aclPermissions(it))
            }
        }

    /** ACL decision for [key]: only objects of its own study, only the permissions its scope maps to. */
    fun permits(key: ApiKeyAuthenticationToken, aclKey: AclKey, required: Set<Permission>): Boolean =
        aclKey.firstOrNull() == key.studyId && aclPermissions(key.scope).containsAll(required)

    /** Scope a study permission needs; null where no key may ever act (ACLs, audit log, system admin). */
    fun requiredScope(permission: StudyPermission): ApiKeyScope? = when (permission) {
        StudyPermission.READ_STUDY,
        StudyPermission.READ_PARTICIPANT_DATA,
        StudyPermission.EXPORT_DATA -> ApiKeyScope.READ_ONLY

        StudyPermission.MODIFY_STUDY,
        StudyPermission.MANAGE_PARTICIPANTS,
        StudyPermission.MANAGE_SURVEYS -> ApiKeyScope.WRITE

        StudyPermission.DELETE_DATA -> ApiKeyScope.ADMIN

        StudyPermission.VIEW_AUDIT_LOG,
        StudyPermission.MANAGE_PERMISSIONS,
        StudyPermission.ADMIN -> null
    }

    fun permits(key: ApiKeyAuthenticationToken, studyId: UUID, permission: StudyPermission): Boolean =
        key.studyId == studyId && requiredScope(permission)?.let { covers(key.scope, it) } == true
}
