package com.openlattice.chronicle.e2e.dsl.scopes

import com.openlattice.chronicle.client.ChronicleClient
import com.openlattice.chronicle.e2e.dsl.ChronicleTestDsl
import com.openlattice.chronicle.e2e.dsl.ScenarioContext
import com.openlattice.chronicle.export.ExportRequest
import com.openlattice.chronicle.participants.Participant
import java.util.UUID

@ChronicleTestDsl
class StudyScope(
    val ctx: ScenarioContext,
    val userId: String,
    val client: ChronicleClient,
    val studyId: UUID,
) {
    fun participant(
        participant: Participant = ctx.providers.data.participant(),
        block: ParticipantScope.() -> Unit,
    ) {
        client.studyApi.registerParticipant(studyId, participant)
        ctx.pushCleanup {
            client.studyApi.deleteStudyParticipants(studyId, setOf(participant.participantId))
        }
        ParticipantScope(ctx, userId, client, studyId, participant.participantId).block()
    }

    fun export(
        request: ExportRequest = ExportRequest(
            dataTypes = setOf(com.openlattice.chronicle.study.ParticipantDataType.UsageEvents),
        ),
        block: ExportScope.() -> Unit,
    ) {
        val job = client.testExportApi.createAsyncExport(studyId, request)
        ExportScope(ctx, client, studyId, job.exportId).block()
    }
}
