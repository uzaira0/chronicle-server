package com.openlattice.chronicle.deletion

import com.openlattice.chronicle.services.jobs.ChronicleParticipantJobDefinition
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.CHRONICLE_USAGE_EVENTS
import java.util.UUID

/**
 * @author Solomon Tang <solomon@openlattice.com>
 */
public open class DeleteParticipantUsageData (
    override val studyId: UUID,
    override val participantIds: Collection<String>
) : ChronicleParticipantJobDefinition {
    internal companion object {
        public val table: String = CHRONICLE_USAGE_EVENTS.name
    }
}
