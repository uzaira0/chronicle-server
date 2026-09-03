package com.openlattice.chronicle.e2e.dsl.scopes

import com.openlattice.chronicle.client.ChronicleClient
import com.openlattice.chronicle.e2e.dsl.ChronicleTestDsl
import com.openlattice.chronicle.e2e.dsl.ScenarioContext
import java.util.UUID

@ChronicleTestDsl
class DataScope(
    val ctx: ScenarioContext,
    val client: ChronicleClient,
    val studyId: UUID,
    val participantId: String,
    val rowsWritten: Int,
) {
    fun flush() = ctx.providers.flusher.flush(studyId, participantId)

    fun verify(block: DataScope.() -> Unit) = block()
}
