package com.openlattice.chronicle.e2e.dsl.scopes

import com.openlattice.chronicle.e2e.dsl.ChronicleTestDsl
import com.openlattice.chronicle.e2e.dsl.ScenarioContext

@ChronicleTestDsl
class ScenarioScope(val ctx: ScenarioContext) {

    fun asUser(userId: String, block: AuthScope.() -> Unit) {
        val client = ctx.providers.api.clientFor(userId)
        AuthScope(ctx, userId, client).block()
    }
}
