// reason: package intentionally diverges from directory; the FQN is a Hazelcast-serialized class identity and must not change
@file:Suppress("InvalidPackageDeclaration")

package com.openlattice.users.processors.aggregators

import com.openlattice.chronicle.users.ChronicleUserProfile
import com.hazelcast.aggregation.Aggregator

public data class UsersWithConnectionsAggregator(
        val connections: Set<String>,
        val users: MutableSet<ChronicleUserProfile>
) : Aggregator<MutableMap.MutableEntry<String, ChronicleUserProfile>, Set<ChronicleUserProfile>> {

    override fun accumulate(input: MutableMap.MutableEntry<String, ChronicleUserProfile>) {
        if (input.value.connections.any { connections.contains(it) }) {
            users.add(input.value)
        }
    }

    override fun combine(aggregator: Aggregator<*, *>) {
        if (aggregator is UsersWithConnectionsAggregator) {
            users.addAll(aggregator.users)
        }
    }

    override fun aggregate(): Set<ChronicleUserProfile> {
        return users
    }
}
