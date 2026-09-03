package com.openlattice.chronicle.services.jobs

import java.util.*

/**
 * @author Solomon Tang <solomon@openlattice.com>
 */
public interface ChronicleParticipantJobDefinition : ChronicleJobDefinition {
    public val studyId: UUID
    public val participantIds: Collection<String>
}
