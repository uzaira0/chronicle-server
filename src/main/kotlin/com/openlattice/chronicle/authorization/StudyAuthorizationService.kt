/*
 * Copyright (C) 2024. Chronicle.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.openlattice.chronicle.authorization

import com.geekbeast.controllers.exceptions.ForbiddenException
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.principals.Principals
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.*

/**
 * Service responsible for enforcing role-based access control (RBAC) and study-level isolation.
 * Implements HIPAA's "minimum necessary" principle by ensuring users only access data they need for their role.
 *
 * This service provides:
 * - Study-level access control based on user roles
 * - Permission checking for specific operations
 * - Audit logging for authorization failures
 *
 * Note: Participants do NOT have system accounts. They submit data via mobile API with app key
 * authentication only and never read data back through the system. This service does NOT apply
 * to mobile API endpoints.
 *
 * @author uzaira0
 */
@Service
public open class StudyAuthorizationService(
    private val authorizationManager: AuthorizationManager,
    private val auditingManager: AuditingManager
) {

    public companion object {
        private val logger = LoggerFactory.getLogger(StudyAuthorizationService::class.java)
    }

    /**
     * Checks if the current user can access a study (read access).
     *
     * @param studyId The UUID of the study to check access for.
     * @return true if the user can access the study, false otherwise.
     */
    public fun canAccessStudy(studyId: UUID): Boolean {
        return canAccessStudy(Principals.getCurrentPrincipals(), studyId)
    }

    /**
     * Checks if a set of principals can access a study (read access).
     *
     * @param principals The set of principals to check.
     * @param studyId The UUID of the study to check access for.
     * @return true if any principal can access the study, false otherwise.
     */
    public fun canAccessStudy(principals: NavigableSet<Principal>, studyId: UUID): Boolean {
        // System admins have full access
        if (isSystemAdmin(principals)) {
            return true
        }

        // Check if user has at least READ permission on the study
        val aclKey = AclKey(studyId)
        return authorizationManager.checkIfHasPermissions(
            aclKey,
            principals,
            READ_PERMISSION
        )
    }

    /**
     * Checks if the current user can modify a study (write access).
     *
     * @param studyId The UUID of the study to check access for.
     * @return true if the user can modify the study, false otherwise.
     */
    public fun canModifyStudy(studyId: UUID): Boolean {
        return canModifyStudy(Principals.getCurrentPrincipals(), studyId)
    }

    /**
     * Checks if a set of principals can modify a study (write access).
     *
     * @param principals The set of principals to check.
     * @param studyId The UUID of the study to check access for.
     * @return true if any principal can modify the study, false otherwise.
     */
    public fun canModifyStudy(principals: NavigableSet<Principal>, studyId: UUID): Boolean {
        // System admins have full access
        if (isSystemAdmin(principals)) {
            return true
        }

        // Check if user has WRITE permission on the study
        val aclKey = AclKey(studyId)
        return authorizationManager.checkIfHasPermissions(
            aclKey,
            principals,
            WRITE_PERMISSION
        )
    }

    /**
     * Checks if the current user can manage participants in a study.
     * Requires WRITE permission on the study.
     *
     * @param studyId The UUID of the study to check access for.
     * @return true if the user can manage participants, false otherwise.
     */
    public fun canManageParticipants(studyId: UUID): Boolean {
        return canManageParticipants(Principals.getCurrentPrincipals(), studyId)
    }

    /**
     * Checks if a set of principals can manage participants in a study.
     *
     * @param principals The set of principals to check.
     * @param studyId The UUID of the study to check access for.
     * @return true if any principal can manage participants, false otherwise.
     */
    public fun canManageParticipants(principals: NavigableSet<Principal>, studyId: UUID): Boolean {
        // System admins have full access
        if (isSystemAdmin(principals)) {
            return true
        }

        // Participant management requires WRITE permission
        val aclKey = AclKey(studyId)
        return authorizationManager.checkIfHasPermissions(
            aclKey,
            principals,
            WRITE_PERMISSION
        )
    }

    /**
     * Checks if the current user can export data from a study.
     * Requires READ permission on the study.
     *
     * @param studyId The UUID of the study to check access for.
     * @return true if the user can export data, false otherwise.
     */
    public fun canExportData(studyId: UUID): Boolean {
        return canExportData(Principals.getCurrentPrincipals(), studyId)
    }

    /**
     * Checks if a set of principals can export data from a study.
     *
     * @param principals The set of principals to check.
     * @param studyId The UUID of the study to check access for.
     * @return true if any principal can export data, false otherwise.
     */
    public fun canExportData(principals: NavigableSet<Principal>, studyId: UUID): Boolean {
        // System admins have full access
        if (isSystemAdmin(principals)) {
            return true
        }

        // Data export requires READ permission
        val aclKey = AclKey(studyId)
        return authorizationManager.checkIfHasPermissions(
            aclKey,
            principals,
            READ_PERMISSION
        )
    }

    /**
     * Checks if the current user owns a study.
     * Required for destructive operations like deleting the study.
     *
     * @param studyId The UUID of the study to check ownership for.
     * @return true if the user owns the study, false otherwise.
     */
    public fun ownsStudy(studyId: UUID): Boolean {
        return ownsStudy(Principals.getCurrentPrincipals(), studyId)
    }

    /**
     * Checks if a set of principals owns a study.
     *
     * @param principals The set of principals to check.
     * @param studyId The UUID of the study to check ownership for.
     * @return true if any principal owns the study, false otherwise.
     */
    public fun ownsStudy(principals: NavigableSet<Principal>, studyId: UUID): Boolean {
        // System admins are treated as owners for administrative purposes
        if (isSystemAdmin(principals)) {
            return true
        }

        val aclKey = AclKey(studyId)
        return authorizationManager.checkIfHasPermissions(
            aclKey,
            principals,
            OWNER_PERMISSION
        )
    }

    /**
     * Checks if the current user is a system administrator.
     *
     * @return true if the user is a system admin, false otherwise.
     */
    public fun isSystemAdmin(): Boolean {
        return isSystemAdmin(Principals.getCurrentPrincipals())
    }

    /**
     * Checks if a set of principals includes the system admin role.
     *
     * @param principals The set of principals to check.
     * @return true if any principal is a system admin, false otherwise.
     */
    public fun isSystemAdmin(principals: NavigableSet<Principal>): Boolean {
        return principals.contains(Principals.getAdminRole())
    }

    /**
     * Requires study access and throws ForbiddenException if access is denied.
     * Logs the access denial to the audit log.
     *
     * @param studyId The UUID of the study to check access for.
     * @param permission The permission being requested (for logging).
     * @throws ForbiddenException if access is denied.
     */
    public fun requireStudyAccess(studyId: UUID, permission: StudyPermission = StudyPermission.READ_STUDY) {
        val principals = Principals.getCurrentPrincipals()
        if (!canAccessStudy(principals, studyId)) {
            logUnauthorizedAccess(studyId, permission)
            throw ForbiddenException("Access denied to study $studyId")
        }
    }

    /**
     * Requires study modification permission and throws ForbiddenException if access is denied.
     * Logs the access denial to the audit log.
     *
     * @param studyId The UUID of the study to check access for.
     * @throws ForbiddenException if access is denied.
     */
    public fun requireStudyModification(studyId: UUID) {
        val principals = Principals.getCurrentPrincipals()
        if (!canModifyStudy(principals, studyId)) {
            logUnauthorizedAccess(studyId, StudyPermission.MODIFY_STUDY)
            throw ForbiddenException("Modification access denied to study $studyId")
        }
    }

    /**
     * Requires participant management permission and throws ForbiddenException if access is denied.
     * Logs the access denial to the audit log.
     *
     * @param studyId The UUID of the study to check access for.
     * @throws ForbiddenException if access is denied.
     */
    public fun requireParticipantManagement(studyId: UUID) {
        val principals = Principals.getCurrentPrincipals()
        if (!canManageParticipants(principals, studyId)) {
            logUnauthorizedAccess(studyId, StudyPermission.MANAGE_PARTICIPANTS)
            throw ForbiddenException("Participant management access denied to study $studyId")
        }
    }

    /**
     * Requires data export permission and throws ForbiddenException if access is denied.
     * Logs the access denial to the audit log.
     *
     * @param studyId The UUID of the study to check access for.
     * @throws ForbiddenException if access is denied.
     */
    public fun requireDataExport(studyId: UUID) {
        val principals = Principals.getCurrentPrincipals()
        if (!canExportData(principals, studyId)) {
            logUnauthorizedAccess(studyId, StudyPermission.EXPORT_DATA)
            throw ForbiddenException("Data export access denied to study $studyId")
        }
    }

    /**
     * Requires study ownership and throws ForbiddenException if not an owner.
     * Logs the access denial to the audit log.
     *
     * @param studyId The UUID of the study to check ownership for.
     * @throws ForbiddenException if not an owner.
     */
    public fun requireStudyOwnership(studyId: UUID) {
        val principals = Principals.getCurrentPrincipals()
        if (!ownsStudy(principals, studyId)) {
            logUnauthorizedAccess(studyId, StudyPermission.ADMIN)
            throw ForbiddenException("Ownership access denied to study $studyId")
        }
    }

    /**
     * Requires system admin role and throws ForbiddenException if not an admin.
     * Logs the access denial to the audit log.
     *
     * @throws ForbiddenException if not a system admin.
     */
    public fun requireSystemAdmin() {
        if (!isSystemAdmin()) {
            val currentUser = Principals.getCurrentSecurablePrincipal()
            logger.warn(
                "Unauthorized system admin access attempt by user {} ({})",
                currentUser.title,
                currentUser.principal.id
            )
            auditingManager.recordEvents(
                listOf(
                    AuditableEvent(
                        aclKey = AclKey(currentUser.id),
                        eventType = AuditEventType.AUTHORIZATION_CHECK_FAILED,
                        description = "System admin access denied for user ${currentUser.title}",
                        data = mapOf(
                            "requiredRole" to "SYSTEM_ADMIN",
                            "userId" to currentUser.principal.id
                        )
                    )
                )
            )
            throw ForbiddenException("System administrator access required")
        }
    }

    /**
     * Checks a specific permission for the study and throws if not authorized.
     *
     * @param studyId The UUID of the study.
     * @param permission The permission to check.
     * @throws ForbiddenException if the permission is not granted.
     */
    public fun requirePermission(studyId: UUID, permission: StudyPermission) {
        when (permission) {
            StudyPermission.READ_STUDY,
            StudyPermission.READ_PARTICIPANT_DATA -> requireStudyAccess(studyId, permission)

            StudyPermission.MODIFY_STUDY -> requireStudyModification(studyId)

            StudyPermission.MANAGE_PARTICIPANTS -> requireParticipantManagement(studyId)

            StudyPermission.EXPORT_DATA -> requireDataExport(studyId)

            StudyPermission.MANAGE_SURVEYS -> requireStudyModification(studyId)

            StudyPermission.VIEW_AUDIT_LOG -> requireStudyOwnership(studyId)

            StudyPermission.MANAGE_PERMISSIONS -> requireStudyOwnership(studyId)

            StudyPermission.DELETE_DATA -> requireStudyOwnership(studyId)

            StudyPermission.ADMIN -> requireSystemAdmin()
        }
    }

    /**
     * Gets the effective role for the current user on a specific study.
     *
     * @param studyId The UUID of the study.
     * @return The ChronicleStudyRole for the user on this study, or null if no access.
     */
    public fun getEffectiveRole(studyId: UUID): ChronicleStudyRole? {
        val principals = Principals.getCurrentPrincipals()

        // Check for system admin first
        if (isSystemAdmin(principals)) {
            return ChronicleStudyRole.SYSTEM_ADMIN
        }

        val aclKey = AclKey(studyId)

        // Check for ownership (study admin)
        if (authorizationManager.checkIfHasPermissions(aclKey, principals, OWNER_PERMISSION)) {
            return ChronicleStudyRole.STUDY_ADMIN
        }

        // Check for read access (researcher)
        if (authorizationManager.checkIfHasPermissions(aclKey, principals, READ_PERMISSION)) {
            return ChronicleStudyRole.RESEARCHER
        }

        return null
    }

    /**
     * Logs an unauthorized access attempt to the audit log.
     *
     * @param studyId The UUID of the study that was accessed.
     * @param permission The permission that was denied.
     */
    private fun logUnauthorizedAccess(studyId: UUID, permission: StudyPermission) {
        val currentUser = Principals.getCurrentSecurablePrincipal()
        logger.warn(
            "Unauthorized access attempt to study {} by user {} ({}) - permission: {}",
            studyId,
            currentUser.title,
            currentUser.principal.id,
            permission.name
        )

        auditingManager.recordEvents(
            listOf(
                AuditableEvent(
                    aclKey = AclKey(studyId),
                    securablePrincipalId = currentUser.id,
                    principal = currentUser.principal,
                    eventType = AuditEventType.AUTHORIZATION_CHECK_FAILED,
                    description = "Access denied for permission ${permission.name}",
                    study = studyId,
                    data = mapOf(
                        "permission" to permission.name,
                        "userId" to currentUser.principal.id,
                        "userName" to currentUser.title
                    )
                )
            )
        )
    }
}
