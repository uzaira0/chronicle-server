package com.openlattice.chronicle.storage

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.storage.rls.RLSContextManager
import org.junit.Assert
import org.junit.Test

/**
 * Verify that the RLSContextManager bean exists in the application context (Item 1).
 */
class RLSContextManagerTest : ChronicleServerTests() {

    @Test
    fun testRLSContextManagerBeanExists() {
        val bean = testServer.context.getBean(RLSContextManager::class.java)
        Assert.assertNotNull("RLSContextManager bean should exist after registering RLSSecurityPod", bean)
    }
}
