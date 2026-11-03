package com.openlattice.chronicle.authorization.aspects

import com.geekbeast.controllers.exceptions.ForbiddenException
import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.authorization.annotations.RequiresStudyAccess
import com.openlattice.chronicle.authorization.annotations.StudyId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.aop.framework.ProxyFactory
import java.util.UUID

/**
 * @RequiresStudyAccess is enforced by StudyAuthorizationInterceptor through Spring AOP alone
 * (spring-aop, Apache-2.0); aspectjweaver (EPL-2.0, GPL-incompatible) is no longer shipped.
 */
class StudyAuthorizationInterceptorTest {
    open class Controller {
        val calls = mutableListOf<String>()

        @RequiresStudyAccess(StudyPermission.EXPORT_DATA)
        open fun byName(studyId: UUID): String = "named".also { calls += it }

        @RequiresStudyAccess(StudyPermission.MODIFY_STUDY)
        open fun byAnnotation(other: UUID, @StudyId target: UUID): String = "annotated".also { calls += it }

        @RequiresStudyAccess(StudyPermission.DELETE_DATA, studyIdParam = "study")
        open fun byCustomName(study: UUID): String = "custom".also { calls += it }

        @RequiresStudyAccess(StudyPermission.READ_STUDY)
        open fun withoutStudyId(participantId: UUID): String = "unreachable".also { calls += it }

        open fun unannotated(studyId: UUID): String = "open".also { calls += it }
    }

    private val access = mock(StudyAuthorizationService::class.java)
    private val target = Controller()
    private val proxy = ProxyFactory(target).apply {
        isProxyTargetClass = true
        addAdvisor(StudyAuthorizationInterceptor.advisor { access })
    }.proxy as Controller

    @Test
    fun theStudyNamedByTheParameterIsCheckedBeforeTheMethodRuns() {
        val study = UUID.randomUUID()
        assertEquals("named", proxy.byName(study))
        verify(access).requirePermission(study, StudyPermission.EXPORT_DATA)
    }

    @Test
    fun aStudyIdAnnotationWinsOverTheParameterName() {
        val other = UUID.randomUUID()
        val study = UUID.randomUUID()
        assertEquals("annotated", proxy.byAnnotation(other, study))
        verify(access).requirePermission(study, StudyPermission.MODIFY_STUDY)
    }

    @Test
    fun theAnnotationCanNameAnotherParameter() {
        val study = UUID.randomUUID()
        assertEquals("custom", proxy.byCustomName(study))
        verify(access).requirePermission(study, StudyPermission.DELETE_DATA)
    }

    @Test
    fun aDeniedCheckStopsTheCallBeforeTheMethodRuns() {
        val study = UUID.randomUUID()
        doThrow(ForbiddenException("Denied")).`when`(access).requirePermission(study, StudyPermission.EXPORT_DATA)
        assertThrows(ForbiddenException::class.java) { proxy.byName(study) }
        assertTrue(target.calls.isEmpty())
    }

    @Test
    fun aMethodWithNoStudyIdFailsClosed() {
        val refused = assertThrows(IllegalStateException::class.java) { proxy.withoutStudyId(UUID.randomUUID()) }
        assertTrue(refused.message!!.contains("'studyId'"))
        assertTrue(target.calls.isEmpty())
        verifyNoInteractions(access)
    }

    @Test
    fun anUnannotatedMethodIsNotChecked() {
        assertEquals("open", proxy.unannotated(UUID.randomUUID()))
        verifyNoInteractions(access)
    }

    @Test
    fun aspectJIsNotOnTheRuntimeClasspath() {
        // The GPL-3.0 server must not ship aspectjweaver (EPL-2.0 without the secondary-license designation).
        assertThrows(ClassNotFoundException::class.java) { Class.forName("org.aspectj.lang.annotation.Aspect") }
    }
}
