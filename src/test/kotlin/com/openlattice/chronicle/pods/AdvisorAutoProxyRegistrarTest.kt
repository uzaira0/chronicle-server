package com.openlattice.chronicle.pods

import org.aopalliance.intercept.MethodInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.aop.Advisor
import org.springframework.aop.framework.Advised
import org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator
import org.springframework.aop.support.AopUtils
import org.springframework.aop.support.DefaultPointcutAdvisor
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import java.util.concurrent.atomic.AtomicInteger

/**
 * Without @EnableAspectJAutoProxy (it needs aspectjweaver, EPL-2.0), every context still has exactly one
 * auto-proxy creator, and it applies every Advisor bean: method security's infrastructure advisors and
 * plain ones such as rhizome's @Timed advisor and the @RequiresStudyAccess advisor.
 */
class AdvisorAutoProxyRegistrarTest {
    @Target(AnnotationTarget.FUNCTION)
    @Retention(AnnotationRetention.RUNTIME)
    annotation class Counted

    open class Service {
        @Counted
        open fun counted(): String = "counted"

        @PreAuthorize("denyAll()")
        open fun secured(): String = "secured"
    }

    @Configuration
    @Import(AdvisorAutoProxyRegistrar::class)
    open class PlainAdvisorPod {
        @Bean
        open fun counter(): AtomicInteger = AtomicInteger()

        @Bean
        open fun countedAdvisor(counter: AtomicInteger): Advisor =
            DefaultPointcutAdvisor(AnnotationMatchingPointcut.forMethodAnnotation(Counted::class.java),
                MethodInterceptor { invocation -> counter.incrementAndGet(); invocation.proceed() })

        @Bean
        open fun service(): Service = Service()
    }

    @Configuration
    @EnableMethodSecurity(proxyTargetClass = true)
    @Import(PlainAdvisorPod::class)
    open class SecuredPod

    @Test
    fun aContextWithMethodSecurityHasOneCreatorApplyingEveryAdvisorOnce() {
        AnnotationConfigApplicationContext(SecuredPod::class.java).use { context ->
            val creators = context.getBeansOfType(AbstractAutoProxyCreator::class.java).values
            assertEquals(listOf(DefaultAdvisorAutoProxyCreator::class.java), creators.map { it.javaClass })
            val service = context.getBean(Service::class.java)
            assertTrue(AopUtils.isCglibProxy(service))
            assertEquals("counted", service.counted())
            assertEquals(1, context.getBean(AtomicInteger::class.java).get())
            assertThrows(AccessDeniedException::class.java) { service.secured() }
            // One proxy, not a proxy of a proxy: the target behind the advisors is the plain object.
            assertEquals(Service::class.java, (service as Advised).targetSource.targetClass)
        }
    }

    @Test
    fun aContextWithoutMethodSecurityStillGetsTheCreator() {
        AnnotationConfigApplicationContext(PlainAdvisorPod::class.java).use { context ->
            val creators = context.getBeansOfType(AbstractAutoProxyCreator::class.java).values
            assertEquals(listOf(DefaultAdvisorAutoProxyCreator::class.java), creators.map { it.javaClass })
            assertEquals("counted", context.getBean(Service::class.java).counted())
            assertEquals(1, context.getBean(AtomicInteger::class.java).get())
        }
    }
}
