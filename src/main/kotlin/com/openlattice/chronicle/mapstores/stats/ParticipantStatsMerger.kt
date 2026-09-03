package com.openlattice.chronicle.mapstores.stats

import com.geekbeast.rhizome.hazelcast.processors.AbstractRhizomeEntryProcessor
import com.openlattice.chronicle.participants.ParticipantStats

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@getmethodic.com&gt;
 */
public open class ParticipantStatsMerger(public val statsToMerge: ParticipantStats) :
    AbstractRhizomeEntryProcessor<ParticipantKey, ParticipantStats, Void?>() {
    override fun process(entry: MutableMap.MutableEntry<ParticipantKey, ParticipantStats?>): Void? {
        entry.setValue(mergeParticipantStats(entry.value, statsToMerge))
        return null
    }
}

internal fun mergeParticipantStats(
    current: ParticipantStats?,
    statsToMerge: ParticipantStats,
): ParticipantStats {
    if (current == null) {
        return statsToMerge
    }

    return ParticipantStats(
        studyId = current.studyId,
        participantId = current.participantId,
        androidLastPing = maxOrFirstNotNull(current.androidLastPing, statsToMerge.androidLastPing),
        androidFirstDate = minOrFirstNotNull(current.androidFirstDate, statsToMerge.androidFirstDate),
        androidLastDate = maxOrFirstNotNull(current.androidLastDate, statsToMerge.androidLastDate),
        androidUniqueDates = current.androidUniqueDates + statsToMerge.androidUniqueDates,
        iosLastPing = maxOrFirstNotNull(current.iosLastPing, statsToMerge.iosLastPing),
        iosFirstDate = minOrFirstNotNull(current.iosFirstDate, statsToMerge.iosFirstDate),
        iosLastDate = maxOrFirstNotNull(current.iosLastDate, statsToMerge.iosLastDate),
        iosUniqueDates = current.iosUniqueDates + statsToMerge.iosUniqueDates,
        tudFirstDate = minOrFirstNotNull(current.tudFirstDate, statsToMerge.tudFirstDate),
        tudLastDate = maxOrFirstNotNull(current.tudLastDate, statsToMerge.tudLastDate),
        tudUniqueDates = current.tudUniqueDates + statsToMerge.tudUniqueDates,
    )
}

private fun <T : Comparable<T>> minOrFirstNotNull(a: T?, b: T?): T? =
    when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }

private fun <T : Comparable<T>> maxOrFirstNotNull(a: T?, b: T?): T? =
    when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }
