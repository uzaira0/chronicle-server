package com.openlattice.chronicle.storage

import com.openlattice.chronicle.services.upload.UsageEventQueueEntry
import java.sql.Connection

/** Looks up all participant watermarks once for the claimed/admitted batch. */
internal object ErasedUsageEventFilter {
    fun retainPermitted(connection: Connection, entries: List<UsageEventQueueEntry>): List<UsageEventQueueEntry> {
        val cutoffs = ParticipantPurgeCutoff.load(connection, entries.map {
            ParticipantPurgeCutoff.Subject(it.studyId, it.participantId)
        })
        return entries.filter { entry ->
            val capturedAt = odtFromUsageEventColumn(entry.data[PostgresEventColumns.TIMESTAMP.name]?.value)
            ParticipantPurgeCutoff.permits(
                cutoffs[ParticipantPurgeCutoff.Subject(entry.studyId, entry.participantId)], capturedAt,
            )
        }
    }
}
