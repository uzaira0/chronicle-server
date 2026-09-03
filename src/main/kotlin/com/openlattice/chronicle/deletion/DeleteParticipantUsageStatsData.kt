package com.openlattice.chronicle.deletion

import com.openlattice.chronicle.services.jobs.ChronicleParticipantJobDefinition
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.CHRONICLE_USAGE_STATS
import java.util.UUID

public open class DeleteParticipantUsageStatsData(
    override val studyId: UUID,
    override val participantIds: Collection<String>
) : ChronicleParticipantJobDefinition {
    internal companion object {
        public val table: String = CHRONICLE_USAGE_STATS.name
    }
}
