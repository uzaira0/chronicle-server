package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.authorization.SystemRole
import com.openlattice.chronicle.authorization.principals.Principals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TestSecurityUtilsTest {
    @Test
    fun workerAuthenticationUsesThePreparedPrincipalFixture() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            TestSecurityUtils.setupSecurityContext("worker-user", admin = true)
            val principals = worker.submit<Set<Principal>> {
                TestSecurityUtils.authenticate("worker-user")
                try { Principals.getCurrentPrincipals().toSet() }
                finally { TestSecurityUtils.clearSecurityContext() }
            }.get(5, TimeUnit.SECONDS)
            assertEquals(setOf(Principal(PrincipalType.USER, "worker-user"), SystemRole.adminRole), principals)
        } finally {
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            TestSecurityUtils.initPrincipalsIfNeeded()
            TestSecurityUtils.clearSecurityContext()
        }
    }

    @Test
    fun recompositionReplacesThePreviousSubjectAndRoles() {
        try {
            TestSecurityUtils.setupSecurityContext("first-user", admin = true)
            assertEquals(setOf(Principal(PrincipalType.USER, "first-user"), SystemRole.adminRole),
                Principals.getCurrentPrincipals())

            TestSecurityUtils.setupSecurityContext("second-user", admin = false)
            assertEquals(setOf(Principal(PrincipalType.USER, "second-user")), Principals.getCurrentPrincipals())
            assertEquals("second-user", Principals.getCurrentSecurablePrincipal().principal.id)
        } finally {
            TestSecurityUtils.initPrincipalsIfNeeded()
            TestSecurityUtils.clearSecurityContext()
        }
    }
}
