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

import java.util.*

/**
 * Interface for controllers that need study-level authorization.
 * Provides convenience methods for checking study permissions using the new
 * role-based access control system.
 *
 * This interface works alongside AuthorizingComponent but provides study-specific
 * authorization methods that are more semantically meaningful for Chronicle's
 * study-based security model.
 *
 * Usage:
 * ```kotlin
 * @RestController
 * class MyController(
 *     override val studyAuthorizationService: StudyAuthorizationService,
 *     // ... other dependencies
 * ) : StudyAuthorizingComponent {
 *
 *     fun someMethod(studyId: UUID) {
 *         // Method 1: Use require* methods (throws ForbiddenException)
 *         requireStudyAccess(studyId)
 *
 *         // Method 2: Use can* methods (returns boolean)
 *         if (canModifyStudy(studyId)) { ... }
 *     }
 * }
 * ```
 *
 * Note: For mobile API endpoints that handle participant data submission,
 * use app key authentication instead of this authorization system.
 *
 * @author uzaira0
 */
// reason: cohesive study-level authorization contract (require*/can* permission checks); the
// method count is the public API surface and must not be split
@Suppress("TooManyFunctions")
public interface StudyAuthorizingComponent {

    /**
     * The StudyAuthorizationService used for authorization checks.
     */
    public val studyAuthorizationService: StudyAuthorizationService

    /**
     * Checks if the current user can access a study (read access).
     *
     * @param studyId The UUID of the study.
     * @return true if the user can access the study.
     */
    public fun canAccessStudy(studyId: UUID): Boolean {
        return studyAuthorizationService.canAccessStudy(studyId)
    }

    /**
     * Checks if the current user can modify a study (write access).
     *
     * @param studyId The UUID of the study.
     * @return true if the user can modify the study.
     */
    public fun canModifyStudy(studyId: UUID): Boolean {
        return studyAuthorizationService.canModifyStudy(studyId)
    }

    /**
     * Checks if the current user can manage participants in a study.
     *
     * @param studyId The UUID of the study.
     * @return true if the user can manage participants.
     */
    public fun canManageParticipants(studyId: UUID): Boolean {
        return studyAuthorizationService.canManageParticipants(studyId)
    }

    /**
     * Checks if the current user can export data from a study.
     *
     * @param studyId The UUID of the study.
     * @return true if the user can export data.
     */
    public fun canExportData(studyId: UUID): Boolean {
        return studyAuthorizationService.canExportData(studyId)
    }

    /**
     * Checks if the current user owns a study.
     *
     * @param studyId The UUID of the study.
     * @return true if the user owns the study.
     */
    public fun ownsStudy(studyId: UUID): Boolean {
        return studyAuthorizationService.ownsStudy(studyId)
    }

    /**
     * Checks if the current user is a system administrator.
     *
     * @return true if the user is a system admin.
     */
    public fun isSystemAdmin(): Boolean {
        return studyAuthorizationService.isSystemAdmin()
    }

    /**
     * Requires study access and throws ForbiddenException if denied.
     *
     * @param studyId The UUID of the study.
     * @param permission The permission being requested (for audit logging).
     */
    public fun requireStudyAccess(studyId: UUID, permission: StudyPermission = StudyPermission.READ_STUDY) {
        studyAuthorizationService.requireStudyAccess(studyId, permission)
    }

    /**
     * Requires study modification permission and throws ForbiddenException if denied.
     *
     * @param studyId The UUID of the study.
     */
    public fun requireStudyModification(studyId: UUID) {
        studyAuthorizationService.requireStudyModification(studyId)
    }

    /**
     * Requires participant management permission and throws ForbiddenException if denied.
     *
     * @param studyId The UUID of the study.
     */
    public fun requireParticipantManagement(studyId: UUID) {
        studyAuthorizationService.requireParticipantManagement(studyId)
    }

    /**
     * Requires data export permission and throws ForbiddenException if denied.
     *
     * @param studyId The UUID of the study.
     */
    public fun requireDataExport(studyId: UUID) {
        studyAuthorizationService.requireDataExport(studyId)
    }

    /**
     * Requires study ownership and throws ForbiddenException if denied.
     *
     * @param studyId The UUID of the study.
     */
    public fun requireStudyOwnership(studyId: UUID) {
        studyAuthorizationService.requireStudyOwnership(studyId)
    }

    /**
     * Requires system admin role and throws ForbiddenException if denied.
     */
    public fun requireSystemAdmin() {
        studyAuthorizationService.requireSystemAdmin()
    }

    /**
     * Gets the effective role for the current user on a specific study.
     *
     * @param studyId The UUID of the study.
     * @return The ChronicleStudyRole for the user, or null if no access.
     */
    public fun getEffectiveRole(studyId: UUID): ChronicleStudyRole? {
        return studyAuthorizationService.getEffectiveRole(studyId)
    }
}
