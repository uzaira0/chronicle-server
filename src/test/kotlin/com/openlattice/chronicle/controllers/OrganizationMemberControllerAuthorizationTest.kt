package com.openlattice.chronicle.controllers

import com.geekbeast.controllers.exceptions.ForbiddenException
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.authorization.aspects.OrganizationAuthorizationAspect
import com.openlattice.chronicle.organizations.OrganizationMember
import com.openlattice.chronicle.organizations.OrganizationRole
import com.openlattice.chronicle.services.organizations.OrganizationMemberService
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory
import java.util.UUID

/**
 * `@RequiresOrganizationAccess` was inert: OrganizationAuthorizationAspect carries @Aspect
 * and @Component but its package is outside ChronicleServerMvcPod's controller-only component
 * scan, and StudyAuthorizationPod declared only the study aspect, so nothing registered it.
 * Every organization endpoint therefore ran with authentication only — an outsider could POST
 * themselves as OWNER of any organization.
 */
class OrganizationMemberControllerAuthorizationTest {

    private val memberService = Mockito.mock(OrganizationMemberService::class.java)
    private val organizationId = UUID.randomUUID()

    @Before
    fun setUp() {
        TestSecurityUtils.setupSecurityContext(subject = "outsider", admin = false)
    }

    @After
    fun tearDown() {
        TestSecurityUtils.clearSecurityContext()
    }

    private fun securedController(): OrganizationMemberController {
        val factory = AspectJProxyFactory(OrganizationMemberController(memberService))
        factory.isProxyTargetClass = true
        factory.addAspect(OrganizationAuthorizationAspect(memberService))
        return factory.getProxy()
    }

    @Test
    fun aNonMemberCannotGrantThemselvesOwnership() {
        Mockito.`when`(memberService.getMemberRole(organizationId, "outsider")).thenReturn(null)

        assertThrows(ForbiddenException::class.java) {
            securedController().addMember(
                organizationId,
                OrganizationMember(organizationId, "outsider", OrganizationRole.OWNER)
            )
        }
        Mockito.verify(memberService, Mockito.never())
            .addMember(organizationId, OrganizationMember(organizationId, "outsider", OrganizationRole.OWNER))
    }

    @Test
    fun aViewerCannotAddMembers() {
        Mockito.`when`(memberService.getMemberRole(organizationId, "outsider"))
            .thenReturn(OrganizationRole.VIEWER)

        assertThrows(ForbiddenException::class.java) {
            securedController().addMember(
                organizationId,
                OrganizationMember(organizationId, "someone-else", OrganizationRole.ADMIN)
            )
        }
        Mockito.verify(memberService, Mockito.never())
            .addMember(organizationId, OrganizationMember(organizationId, "someone-else", OrganizationRole.ADMIN))
    }

    @Test
    fun anAdminCanAddMembers() {
        Mockito.`when`(memberService.getMemberRole(organizationId, "outsider"))
            .thenReturn(OrganizationRole.ADMIN)
        val member = OrganizationMember(organizationId, "someone-else", OrganizationRole.VIEWER)

        securedController().addMember(organizationId, member)

        Mockito.verify(memberService).addMember(organizationId, member)
    }

    @Test
    fun theAspectIsRegisteredByAConfigurationPod() {
        // The aspect's own @Aspect/@Component stereotype is never scanned, so a @Bean declaration
        // is the only thing that makes @RequiresOrganizationAccess enforce anything at runtime.
        val declared = com.openlattice.chronicle.pods.StudyAuthorizationPod::class.java.methods
            .filter { it.isAnnotationPresent(org.springframework.context.annotation.Bean::class.java) }
            .map { it.returnType }
        assertTrue(
            "StudyAuthorizationPod must declare an OrganizationAuthorizationAspect bean",
            declared.contains(OrganizationAuthorizationAspect::class.java),
        )
    }

    @Test
    fun aViewerCannotUpdateQuotas() {
        Mockito.`when`(memberService.getMemberRole(organizationId, "outsider"))
            .thenReturn(OrganizationRole.VIEWER)

        assertThrows(ForbiddenException::class.java) {
            securedController().updateQuotas(
                organizationId,
                com.openlattice.chronicle.organizations.OrganizationQuotas(organizationId)
            )
        }
    }
}
