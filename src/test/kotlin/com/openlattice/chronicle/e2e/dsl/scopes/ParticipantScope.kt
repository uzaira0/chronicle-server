package com.openlattice.chronicle.e2e.dsl.scopes

import com.openlattice.chronicle.client.ChronicleClient
import com.openlattice.chronicle.e2e.dsl.ChronicleTestDsl
import com.openlattice.chronicle.e2e.dsl.ScenarioContext
import com.openlattice.chronicle.sources.AndroidDevice
import java.util.UUID

@ChronicleTestDsl
class ParticipantScope(
    val ctx: ScenarioContext,
    val userId: String,
    val client: ChronicleClient,
    val studyId: UUID,
    val participantId: String,
) {
    fun device(
        device: AndroidDevice = ctx.providers.data.androidDevice(),
        block: DeviceScope.() -> Unit,
    ) {
        val deviceId = "e2e-${device.model}-${UUID.randomUUID()}"
        client.studyApi.enroll(studyId, participantId, deviceId, device)
        DeviceScope(ctx, userId, client, studyId, participantId, deviceId).block()
    }
}
