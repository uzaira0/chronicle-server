package com.openlattice.chronicle.pipeline

import com.openlattice.chronicle.services.jobs.ChronicleStudyJobDefinition
import java.util.*

public data class PipelineJobDefinition(
    override val studyId: UUID,
    val config: PipelineConfig = PipelineConfig(),
) : ChronicleStudyJobDefinition
