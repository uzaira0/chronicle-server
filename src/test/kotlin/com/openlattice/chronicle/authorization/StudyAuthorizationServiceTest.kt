package com.openlattice.chronicle.authorization

import com.geekbeast.controllers.exceptions.ForbiddenException
import com.openlattice.chronicle.audit.RecordingAuditingManager
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.controllers.TestSecurityUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.util.EnumSet
import java.util.UUID

/**
 * Every `@RequiresStudyAccess` permission maps onto exactly one ACL tier on the study it names: a researcher
 * holding READ on study A can read and export A, but cannot edit it, manage its participants, delete its data
 * or see its audit log, and holds nothing on study B. Each refusal leaves an AUTHORIZATION_CHECK_FAILED row.
 */
class StudyAuthorizationServiceTest {
    private val studyA = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    private val studyB = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002")
    private val authorizationManager = Mockito.mock(AuthorizationManager::class.java)
    private val audit = RecordingAuditingManager()
    private val service = StudyAuthorizationService(authorizationManager, audit)

    @After
    fun clear() = TestSecurityUtils.clearSecurityContext()

    /** The researcher holds exactly [held] on study A, nothing anywhere else. */
    private fun holding(held: Set<Permission>, admin: Boolean = false) {
        TestSecurityUtils.setupSecurityContext(subject = "researcher-1", admin = admin)
        Mockito.reset(authorizationManager)
        Mockito.`when`(authorizationManager.checkIfHasPermissions(org.mockito.kotlin.any(), org.mockito.kotlin.any(),
            org.mockito.kotlin.any())).thenAnswer {
            val key = it.getArgument<AclKey>(0)
            val requested = it.getArgument<EnumSet<Permission>>(2)
            key == AclKey(studyA) && held.containsAll(requested)
        }
    }

    private fun allowed(study: UUID, permission: StudyPermission): Boolean = try {
        service.requirePermission(study, permission)
        true
    } catch (_: ForbiddenException) {
        false
    }

    private val readTier = setOf(StudyPermission.READ_STUDY, StudyPermission.READ_PARTICIPANT_DATA, StudyPermission.EXPORT_DATA)
    private val writeTier = setOf(StudyPermission.MODIFY_STUDY, StudyPermission.MANAGE_PARTICIPANTS, StudyPermission.MANAGE_SURVEYS)
    private val ownerTier = setOf(StudyPermission.VIEW_AUDIT_LOG, StudyPermission.MANAGE_PERMISSIONS, StudyPermission.DELETE_DATA)

    @Test
    fun eachStudyPermissionNeedsExactlyItsAclTierOnThatStudy() {
        val expected = mapOf(
            emptySet<Permission>() to emptySet(),
            setOf(Permission.READ) to readTier,
            setOf(Permission.WRITE) to writeTier,
            setOf(Permission.OWNER) to ownerTier,
            setOf(Permission.READ, Permission.WRITE, Permission.OWNER) to readTier + writeTier + ownerTier,
        )
        assertEquals("every permission is classified", StudyPermission.values().toSet(),
            readTier + writeTier + ownerTier + StudyPermission.ADMIN)
        for ((held, grants) in expected) {
            holding(held)
            val granted = StudyPermission.values().filter { allowed(studyA, it) }.toSet()
            assertEquals("holding $held", grants, granted)
            assertTrue("holding $held on A grants nothing on B", StudyPermission.values().none { allowed(studyB, it) })
        }
    }

    @Test
    fun systemAdminPassesEveryCheckOnEveryStudyWithoutAnyAce() {
        holding(emptySet(), admin = true)
        for (study in listOf(studyA, studyB)) {
            StudyPermission.values().forEach { assertTrue("$it on $study", allowed(study, it)) }
        }
        assertTrue(audit.events.isEmpty())
        Mockito.verifyNoInteractions(authorizationManager)
    }

    @Test
    fun everyRefusalIsAuditedWithTheStudyThePermissionAndTheUser() {
        holding(setOf(Permission.READ))
        for (permission in writeTier + ownerTier) {
            audit.observations.clear()
            assertTrue(!allowed(studyA, permission))
            val event = audit.events.single()
            assertEquals(AuditEventType.AUTHORIZATION_CHECK_FAILED, event.eventType)
            assertEquals(studyA, event.study)
            val expectedPermission = when (permission) {
                StudyPermission.MANAGE_SURVEYS -> StudyPermission.MODIFY_STUDY
                in ownerTier -> StudyPermission.ADMIN
                else -> permission
            }
            assertEquals(permission.name, expectedPermission.name, event.data["permission"])
            assertEquals("researcher-1", event.data["userId"])
        }

        audit.observations.clear()
        val refused = runCatching { service.requirePermission(studyA, StudyPermission.ADMIN) }.exceptionOrNull()
        assertTrue(refused is ForbiddenException)
        val event = audit.events.single()
        assertEquals(AuditEventType.AUTHORIZATION_CHECK_FAILED, event.eventType)
        assertEquals(mapOf("requiredRole" to "SYSTEM_ADMIN", "userId" to "researcher-1"), event.data)
    }
}
