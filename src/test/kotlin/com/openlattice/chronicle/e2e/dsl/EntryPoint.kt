package com.openlattice.chronicle.e2e.dsl

import com.openlattice.chronicle.e2e.dsl.di.ProvidersBundle
import com.openlattice.chronicle.e2e.dsl.scopes.ScenarioScope

fun chronicleScenario(providers: ProvidersBundle, swallowCleanupExceptions: Boolean = true, block: ScenarioScope.() -> Unit) {
    val ctx = ScenarioContext(providers)
    try {
        ScenarioScope(ctx).block()
    } finally {
        ctx.cleanup.runAll(swallowExceptions = swallowCleanupExceptions)
    }
}
