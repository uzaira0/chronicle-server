package com.openlattice.chronicle.pipeline

import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.services.jobs.JobService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.util.UUID

class PipelineServiceTest {

    @Test
    fun testTriggerFailsBeforeOpeningAWritePathWhileExecutionSchemaIsUnsupported() {
        val studyId = UUID.randomUUID()
        val storageResolver = Mockito.mock(StorageResolver::class.java)
        val jobService = Mockito.mock(JobService::class.java)
        val idGenerationService = Mockito.mock(HazelcastIdGenerationService::class.java)
        val studyService = Mockito.mock(StudyService::class.java)
        val auditingManager = Mockito.mock(AuditingManager::class.java)
        val config = PipelineConfig(enabled = true)
        val study = Study(
            studyId = studyId,
            title = "pipeline-test",
            contact = "pipeline-test@example.invalid",
            settings = StudySettings(mapOf(StudySettingType.Pipeline to config)),
        )
        Mockito.`when`(studyService.getStudy(studyId)).thenReturn(study)

        val service = PipelineService(
            storageResolver,
            jobService,
            idGenerationService,
            studyService,
            auditingManager,
        )

        val failure = assertThrows(UnsupportedOperationException::class.java) {
            service.triggerPipeline(studyId)
        }

        assertEquals(PipelineJobRunner.EXECUTION_UNAVAILABLE_MESSAGE, failure.message)
        Mockito.verify(studyService).getStudy(studyId)
        Mockito.verifyNoInteractions(storageResolver, jobService, idGenerationService, auditingManager)
    }

    @Test
    fun testInvalidConfigurationsFailClosedBeforeExecutionAvailabilityCheck() {
        val failures = listOf(
            PipelineConfig(enabled = true, steps = emptyList()),
            PipelineConfig(
                enabled = true,
                steps = listOf(
                    PipelineStep(PipelineStepType.DEIDENTIFICATION, 0),
                    PipelineStep(PipelineStepType.AGGREGATION, 0),
                ),
            ),
            PipelineConfig(
                enabled = true,
                steps = listOf(PipelineStep(PipelineStepType.CUSTOM_SQL, 0)),
            ),
            PipelineConfig(enabled = true, outputTable = "unsafe-table;drop"),
            PipelineConfig(enabled = true, timeBucketMinutes = 7),
            PipelineConfig(
                enabled = true,
                steps = listOf(
                    PipelineStep(
                        PipelineStepType.TIME_BUCKETING,
                        0,
                        params = mapOf("bucketMinutes" to "90"),
                    ),
                ),
            ),
        )

        failures.forEach { config ->
            assertThrows(IllegalArgumentException::class.java) {
                PipelineJobRunner.validateConfig(config)
            }
        }
    }

    @Test
    fun testBucketValidationMatchesHourlySqlSemantics() {
        listOf(1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30, 60).forEach { value ->
            assertTrue("expected $value to be supported", PipelineJobRunner.isValidBucketMinutes(value))
        }
        listOf(0, 7, 45, 61, 90, 1_440).forEach { value ->
            assertFalse("expected $value to be rejected", PipelineJobRunner.isValidBucketMinutes(value))
        }
    }
}
