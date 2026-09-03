package com.openlattice.chronicle

import com.geekbeast.rhizome.pods.hazelcast.RegistryBasedHazelcastInstanceConfigurationPod
import com.hazelcast.config.Config
import org.springframework.context.annotation.Configuration

/**
 * Keeps integration tests on the exact isolated member port written into their
 * server and IDS-client fixtures. If that port is occupied, startup must fail
 * instead of silently binding another port while clients connect elsewhere.
 */
@Configuration
open class FailFastTestHazelcastConfigurationPod :
    RegistryBasedHazelcastInstanceConfigurationPod() {
    override fun getHazelcastServerConfiguration(): Config? {
        return super.getHazelcastServerConfiguration()?.apply {
            networkConfig.setPortAutoIncrement(false)
        }
    }
}
