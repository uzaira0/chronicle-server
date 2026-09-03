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
package com.openlattice.chronicle.authorization.annotations

import com.openlattice.chronicle.authorization.StudyPermission

/**
 * Annotation to require study-level access on controller methods.
 * When applied to a method, the StudyAuthorizationAspect will automatically
 * check that the current user has the required permission on the study.
 *
 * The studyId is automatically extracted from method parameters named "studyId"
 * or annotated with @StudyId.
 *
 * Usage:
 * ```kotlin
 * @RequiresStudyAccess(StudyPermission.READ_STUDY)
 * fun getStudy(@PathVariable studyId: UUID): Study { ... }
 *
 * @RequiresStudyAccess(StudyPermission.MODIFY_STUDY)
 * fun updateStudy(@PathVariable studyId: UUID, @RequestBody update: StudyUpdate) { ... }
 * ```
 *
 * Note: This annotation should NOT be used on mobile API endpoints that use
 * app key authentication for participant data submission.
 *
 * @property permission The required StudyPermission to access this endpoint.
 * @property studyIdParam The name of the parameter containing the study ID. Defaults to "studyId".
 *
 * @author uzaira0
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class RequiresStudyAccess(
    /**
     * The permission required to access the annotated method.
     */
    val permission: StudyPermission = StudyPermission.READ_STUDY,

    /**
     * The name of the method parameter that contains the study ID.
     * If not specified, the aspect will look for a parameter named "studyId".
     */
    val studyIdParam: String = "studyId"
)
