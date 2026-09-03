// reason: intentional package layout — the Hazelcast entry-processor FQN
// com.openlattice.ids.processors is part of the serialization contract and is imported as-is
// elsewhere; moving the file would break wire compatibility
@file:Suppress("InvalidPackageDeclaration")

package com.openlattice.ids.processors

import com.hazelcast.core.Offloadable
import com.geekbeast.rhizome.hazelcast.processors.AbstractRhizomeEntryProcessor

/**
 * Used to increment base ids.
 */
public open class LongIdsGeneratingProcessor(public val count: Long) : Offloadable, AbstractRhizomeEntryProcessor<String, Long, Long>() {

    override fun process(entry: MutableMap.MutableEntry<String, Long?>): Long {
        val base = entry.value ?: 0
        entry.setValue(base+count)
        return base
    }

    override fun getExecutorName(): String = Offloadable.OFFLOADABLE_EXECUTOR
}
