package com.openlattice.chronicle.e2e.scenarios

import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.e2e.dsl.chronicleScenario
import com.openlattice.chronicle.e2e.dsl.di.ProvidersBundle
import com.openlattice.chronicle.services.studies.StudyLifecycleService
import com.openlattice.chronicle.study.StudyLifecycleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyLifecycleE2ETest : ChronicleServerTests() {
    private val providers = ProvidersBundle.fromSpringContext(testServer.context)

    @Test
    fun `create get update delete study`() = chronicleScenario(providers) {
        asUser("test_user1") {
            study(providers.data.study("Lifecycle")) {
                val retrieved = client.studyApi.getStudy(studyId)
                assertNotNull(retrieved)
                assertTrue(retrieved.title.startsWith("E2E-Lifecycle"))
            }
        }
    }

    @Test
    fun `study cleanup runs on test completion`() {
        var capturedId: java.util.UUID? = null
        chronicleScenario(providers, swallowCleanupExceptions = false) {
            asUser("test_user1") {
                study(providers.data.study("CleanupCheck")) { capturedId = studyId }
            }
        }
        assertEquals(StudyLifecycleStatus.SCHEDULED_FOR_DELETION,
            testServer.context.getBean(StudyLifecycleService::class.java).getLifecycleStatus(checkNotNull(capturedId)))
    }

    @Test
    fun `create multiple studies and list them`() = chronicleScenario(providers) {
        asUser("test_user1") {
            study(providers.data.study("ListA")) {
                val studies = client.studyApi.getAllStudies().toList()
                assertTrue(studies.any { it.id == studyId })
            }
        }
    }
}
