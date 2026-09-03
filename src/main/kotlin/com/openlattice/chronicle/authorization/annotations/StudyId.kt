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

/**
 * Annotation to mark a method parameter as containing the study ID.
 * Used by StudyAuthorizationAspect to identify which parameter contains
 * the study ID for authorization checks.
 *
 * Usage:
 * ```kotlin
 * @RequiresStudyAccess(StudyPermission.READ_STUDY)
 * fun customMethod(@StudyId customStudyParam: UUID): Study { ... }
 * ```
 *
 * @author uzaira0
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class StudyId
