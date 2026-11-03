package com.openlattice.chronicle.storage.rls

import org.junit.rules.ExternalResource

/** Direct service fixtures act as trusted workers; HTTP and role tests establish their own scope. */
class RLSFixtureContext : ExternalResource() {
    private var previous: RLSConnectionContext? = null

    override fun before() {
        previous = RLSRequestContext.current()
        RLSRequestContext.set(RLSRequestContext.systemContext)
    }

    override fun after() {
        previous?.let(RLSRequestContext::set) ?: RLSRequestContext.clear()
    }
}
