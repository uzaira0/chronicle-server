package com.openlattice.chronicle.testing

import com.hazelcast.config.Config
import java.util.UUID

/**
 * A single-member test cluster that joins nothing and phones nowhere. Auto-detection ignores the
 * multicast flag (a multicast join wait, and parallel PIT minions find each other), and phone-home
 * adds seconds to shutdown; production disables phone-home too.
 */
fun isolatedHazelcastConfig(prefix: String): Config = Config().apply {
    clusterName = "$prefix-${UUID.randomUUID()}"
    networkConfig.join.multicastConfig.isEnabled = false
    networkConfig.join.autoDetectionConfig.isEnabled = false
    setProperty("hazelcast.phone.home.enabled", "false")
}
