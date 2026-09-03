package com.openlattice.chronicle.services.jobs

import java.util.*

/**
 * @author Solomon Tang <solomon@openlattice.com>
 */
public interface ChronicleStudyJobDefinition : ChronicleJobDefinition {
    public val studyId: UUID
}
