package com.openlattice.chronicle.authorization.processors

import com.hazelcast.core.Offloadable
import com.openlattice.chronicle.authorization.AceKey
import com.openlattice.chronicle.authorization.AceValue
import com.openlattice.chronicle.authorization.DelegatedPermissionEnumSet
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.authorization.isActiveAt
import com.geekbeast.rhizome.hazelcast.entryprocessors.AbstractReadOnlyRhizomeEntryProcessor
import java.time.OffsetDateTime
import java.util.*

public open class AuthorizationEntryProcessor(
    private val evaluationTime: OffsetDateTime = OffsetDateTime.now()
) : AbstractReadOnlyRhizomeEntryProcessor<AceKey, AceValue, DelegatedPermissionEnumSet>(), Offloadable {

    override fun process(entry: MutableMap.MutableEntry<AceKey, AceValue?>): DelegatedPermissionEnumSet {
        val permissions = entry.value
            ?.takeIf { it.isActiveAt(evaluationTime) }
            ?.permissions
            ?: EnumSet.noneOf(Permission::class.java)
        return DelegatedPermissionEnumSet.wrap(permissions)
    }

    override fun getExecutorName(): String {
        return Offloadable.OFFLOADABLE_EXECUTOR
    }
}
