package com.openlattice.chronicle.deletion

import com.openlattice.chronicle.services.jobs.ChronicleStudyJobDefinition
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.CHRONICLE_USAGE_EVENTS
import java.util.*

/**
 * @author Solomon Tang <solomon@openlattice.com>
 */
public open class DeleteStudyUsageData(
    override val studyId: UUID
) : ChronicleStudyJobDefinition {
    public val table: String = CHRONICLE_USAGE_EVENTS.name
}
