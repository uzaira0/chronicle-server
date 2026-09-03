package com.openlattice.chronicle.deletion

import com.openlattice.chronicle.services.jobs.ChronicleParticipantJobDefinition
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.NOTIFICATION_ACTIVITY
import java.util.UUID

public open class DeleteParticipantNotificationActivityData(
    override val studyId: UUID,
    override val participantIds: Collection<String>
) : ChronicleParticipantJobDefinition {
    internal companion object {
        public val table: String = NOTIFICATION_ACTIVITY.name
    }
}
