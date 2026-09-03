package com.openlattice.chronicle.deletion

import com.openlattice.chronicle.services.jobs.ChronicleParticipantJobDefinition
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.PREPROCESSED_USAGE_EVENTS
import java.util.UUID

public open class DeleteParticipantPreprocessedUsageData(
    override val studyId: UUID,
    override val participantIds: Collection<String>
) : ChronicleParticipantJobDefinition {
    internal companion object {
        public val table: String = PREPROCESSED_USAGE_EVENTS.name
    }
}
