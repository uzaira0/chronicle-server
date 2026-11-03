package com.openlattice.chronicle.pods

import org.springframework.aop.config.AopConfigUtils
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.core.Ordered

/**
 * Makes the context's one auto-proxy creator a [DefaultAdvisorAutoProxyCreator] that applies every
 * `Advisor` bean in this context and its ancestors, with class-based (CGLIB) proxies.
 *
 * It replaces `@EnableAspectJAutoProxy`, whose creator needs aspectjweaver (EPL-2.0, which may not
 * ship in this GPL-3.0 server). That creator applied every Advisor bean too, so the set of advice is
 * the same: method security, rhizome's `@Timed` advisor and the `@RequiresStudyAccess` advisor.
 *
 * It runs after the configuration classes are parsed, so it sees the creator `@EnableMethodSecurity`
 * registers under [AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME] and takes its place. Registering a
 * second creator instead would wrap each advised bean twice and apply method security twice.
 */
public class AdvisorAutoProxyRegistrar : BeanDefinitionRegistryPostProcessor {
    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        val name = AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME
        if (registry.containsBeanDefinition(name)) {
            registry.removeBeanDefinition(name)
        }
        registry.registerBeanDefinition(name, RootBeanDefinition(DefaultAdvisorAutoProxyCreator::class.java).apply {
            role = BeanDefinition.ROLE_INFRASTRUCTURE
            propertyValues.add("order", Ordered.HIGHEST_PRECEDENCE)
            propertyValues.add("proxyTargetClass", true)
        })
    }
}
