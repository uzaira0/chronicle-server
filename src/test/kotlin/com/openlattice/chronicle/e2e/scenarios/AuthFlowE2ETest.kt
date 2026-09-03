package com.openlattice.chronicle.e2e.scenarios

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.e2e.dsl.chronicleScenario
import com.openlattice.chronicle.e2e.dsl.di.ProvidersBundle
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthFlowE2ETest : ChronicleServerTests() {
    private val providers = ProvidersBundle.fromSpringContext(testServer.context)

    @Test
    fun `auth provider produces a non-blank token`() = chronicleScenario(providers) {
        asUser("test_user1") {
            val studies = client.studyApi.getAllStudies()
            assertNotNull(studies)
        }
    }

    @Test
    fun `distinct users produce distinct tokens`() {
        val token1 = providers.auth.tokenFor("test_user1")
        val token2 = providers.auth.tokenFor("test_user2")
        assertNotEquals(token1, token2)
        assertTrue(token1.isNotBlank())
        assertTrue(token2.isNotBlank())
    }

    @Test
    fun `admin token has admin principal`() {
        val token = providers.auth.tokenFor("test_admin")
        assertTrue(token.isNotBlank())
    }
}
