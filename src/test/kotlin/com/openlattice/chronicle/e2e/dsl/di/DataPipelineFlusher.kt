package com.openlattice.chronicle.e2e.dsl.di

import java.util.UUID

interface DataPipelineFlusher {
    fun flush(studyId: UUID, participantId: String)
}
