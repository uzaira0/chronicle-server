package com.openlattice.chronicle.security

import com.openlattice.chronicle.controllers.TestHookController
import com.openlattice.chronicle.pods.ChronicleServerServletsPod
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.context.annotation.Profile

class RetiredDatastoreAliasTest {
    @Test fun testChronicleServletDoesNotExposeDatastoreAlias() {
        val configuration = ChronicleServerServletsPod().chronicleServlet()
        val mappings = configuration.javaClass.declaredFields
            .first { it.type == Array<String>::class.java }
            .also { it.isAccessible = true }
            .get(configuration) as Array<*>

        assertArrayEquals(arrayOf("/chronicle/*"), mappings)
    }

    @Test fun testTestHooksAreRestrictedToNonProductionProfiles() {
        val profiles = TestHookController::class.java.getAnnotation(Profile::class.java).value.toSet()

        assertEquals(setOf("local & !production", "test & !production"), profiles)
        assertTrue(profiles.all { "!production" in it })
    }
}
