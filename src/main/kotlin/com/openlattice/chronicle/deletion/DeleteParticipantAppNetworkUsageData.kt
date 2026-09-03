package com.openlattice.chronicle.deletion

import com.openlattice.chronicle.services.jobs.ChronicleParticipantJobDefinition
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.APP_NETWORK_USAGE
import java.util.UUID

public open class DeleteParticipantAppNetworkUsageData(
    override val studyId: UUID,
    override val participantIds: Collection<String>
) : ChronicleParticipantJobDefinition {
    internal companion object {
        public val table: String = APP_NETWORK_USAGE.name
    }
}
