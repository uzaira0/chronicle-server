package com.openlattice.chronicle.services.jobs

import com.openlattice.chronicle.ids.IdConstants
import java.util.UUID

/**
 * @author Solomon Tang <solomon@openlattice.com>
 */

public open class EmptyJobDefinition: ChronicleStudyJobDefinition {
    override var studyId: UUID = IdConstants.UNINITIALIZED.id
}
