package com.openlattice.chronicle.deletion

import com.openlattice.chronicle.services.jobs.ChronicleParticipantJobDefinition
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.APP_AUDIO_CONTENT
import java.util.UUID

public open class DeleteParticipantAppAudioContentData(
    override val studyId: UUID,
    override val participantIds: Collection<String>
) : ChronicleParticipantJobDefinition {
    internal companion object {
        public val table: String = APP_AUDIO_CONTENT.name
    }
}
