package com.openlattice.chronicle.deletion

import com.openlattice.chronicle.services.jobs.ChronicleStudyJobDefinition
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.APP_USAGE_SURVEY
import java.util.UUID

/**
 * @author Solomon Tang <solomon@openlattice.com>
 */
public open class DeleteStudyAppUsageSurveyData (
    override val studyId: UUID
) : ChronicleStudyJobDefinition {
    public var table: String = APP_USAGE_SURVEY.name
}
