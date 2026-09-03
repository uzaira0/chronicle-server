package com.openlattice.chronicle.authorization

import com.hazelcast.query.Predicate
import java.time.OffsetDateTime

/**
 * An ACE is effective only before its expiration instant. The boundary is
 * deliberately exclusive: an ACE expiring at [evaluationTime] is already
 * inactive.
 */
internal fun AceValue.isActiveAt(evaluationTime: OffsetDateTime): Boolean {
    return expirationDate.isAfter(evaluationTime)
}

internal fun AceValue.isPermanent(): Boolean = expirationDate == OffsetDateTime.MAX

/**
 * Serializable Hazelcast predicate used alongside the indexed ACL/principal
 * predicates so expired ACEs never reach authorization aggregators.
 */
internal data class ActiveAcePredicate(
    private val evaluationTime: OffsetDateTime
) : Predicate<AceKey, AceValue> {
    override fun apply(entry: Map.Entry<AceKey, AceValue>): Boolean {
        return entry.value.isActiveAt(evaluationTime)
    }
}

internal class PermanentAcePredicate : Predicate<AceKey, AceValue> {
    override fun apply(entry: Map.Entry<AceKey, AceValue>): Boolean {
        return entry.value.isPermanent()
    }
}
