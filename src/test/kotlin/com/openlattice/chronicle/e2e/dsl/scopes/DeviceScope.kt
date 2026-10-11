package com.openlattice.chronicle.e2e.dsl.scopes

import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.client.ChronicleClient
import com.openlattice.chronicle.e2e.dsl.ChronicleTestDsl
import com.openlattice.chronicle.e2e.dsl.ScenarioContext
import com.openlattice.chronicle.study.StudyApi
import java.util.UUID

@ChronicleTestDsl
class DeviceScope(
    val ctx: ScenarioContext,
    val userId: String,
    val client: ChronicleClient,
    val studyId: UUID,
    val participantId: String,
    val deviceId: String,
    val mobileApi: StudyApi,
) {
    fun upload(
        data: ChronicleData = ctx.providers.data.usageEvents(studyId, participantId),
        block: DataScope.() -> Unit,
    ) {
        val rowsWritten = mobileApi.uploadAndroidUsageEventDataV4(studyId, participantId, deviceId, data)
        DataScope(ctx, client, studyId, participantId, rowsWritten).block()
    }
}
