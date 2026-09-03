package com.openlattice.chronicle.pods

import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.storage.rls.RLSDataSources
import com.openlattice.chronicle.storage.rls.RLSContextManager
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.BeansException
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Role
import org.springframework.beans.factory.config.BeanDefinition
import jakarta.inject.Inject

/**
 * Spring configuration pod for Row-Level Security (RLS) components.
 *
 * This pod configures:
 * - RLS context management for database connections
 *
 * Note: The RLS database migration upgrade is registered in ChronicleConfigurationPod
 * to ensure proper ordering with other database upgrades.
 *
 * @author uzaira0
 */
@Configuration
public open class RLSSecurityPod {

    public companion object {
        /**
         * Static infrastructure bean: avoids early instantiation of RLSSecurityPod
         * before Spring property placeholder processing is available.
         */
        @Bean
        @JvmStatic
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        public fun rlsAwareHikariDataSourcePostProcessor(): BeanPostProcessor {
            return object : BeanPostProcessor {
                @Throws(BeansException::class)
                override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
                    return if (bean is HikariDataSource && !bean.toString().startsWith("RLSAwareHikariDataSource(")) {
                        RLSDataSources.wrapIfRequestScoped(bean)
                    } else {
                        bean
                    }
                }
            }
        }
    }

    @Inject
    private lateinit var authorizationManager: AuthorizationManager

    /**
     * RLS Context Manager bean for setting/clearing RLS context on connections.
     *
     * Inject this bean into services that need to set the RLS context on database
     * connections based on the current user's authorization.
     */
    @Bean
    public fun rlsContextManager(): RLSContextManager {
        return RLSContextManager(authorizationManager)
    }

}
