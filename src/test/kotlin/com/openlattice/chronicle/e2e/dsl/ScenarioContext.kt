package com.openlattice.chronicle.e2e.dsl

import com.openlattice.chronicle.e2e.dsl.cleanup.CleanupStack
import com.openlattice.chronicle.e2e.dsl.di.ProvidersBundle

class ScenarioContext(val providers: ProvidersBundle) {
    internal val cleanup = CleanupStack()

    fun pushCleanup(action: () -> Unit) = cleanup.push(action)
}
