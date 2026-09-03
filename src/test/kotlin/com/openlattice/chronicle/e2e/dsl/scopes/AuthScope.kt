package com.openlattice.chronicle.e2e.dsl.scopes

import com.openlattice.chronicle.client.ChronicleClient
import com.openlattice.chronicle.e2e.dsl.ChronicleTestDsl
import com.openlattice.chronicle.e2e.dsl.ScenarioContext
import com.openlattice.chronicle.study.Study

@ChronicleTestDsl
class AuthScope(
    val ctx: ScenarioContext,
    val userId: String,
    val client: ChronicleClient,
) {
    fun study(study: Study = ctx.providers.data.study(), block: StudyScope.() -> Unit) {
        val studyId = client.studyApi.createStudy(study)
        ctx.pushCleanup { client.studyApi.destroyStudy(studyId) }
        StudyScope(ctx, userId, client, studyId).block()
    }
}
