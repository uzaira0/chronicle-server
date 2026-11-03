package com.openlattice.chronicle.contract

import com.openlattice.chronicle.configuration.JacksonSecurityConfig
import com.openlattice.chronicle.study.StudyDuration
import com.openlattice.chronicle.study.StudyLimits
import com.openlattice.chronicle.serializers.StudyLimitsStreamSerializer
import com.hazelcast.internal.serialization.impl.DefaultSerializationServiceBuilder
import jakarta.validation.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.time.OffsetDateTime

class ExactDayStudyLimitsTest {
    /** H-end-default: a study whose researcher set no limits has no protocol end, so nothing ever pauses it. */
    @Test fun emptyLimitsSetNoProtocolEnd() {
        val mapper = JacksonSecurityConfig().secureObjectMapper()
        for (limits in listOf(StudyLimits(), mapper.readValue("{}", StudyLimits::class.java))) {
            assertNull(limits.studyDuration)
            assertNull(limits.studyEnds)
            assertNull(limits.studyDataExpires)
        }
    }

    @Test fun webDayPayloadsPassProductionValidationAndKeepCalendarBoundaryDeadlines() {
        val mapper = JacksonSecurityConfig().secureObjectMapper()
        val fixtures = checkNotNull(javaClass.getResourceAsStream("/exact-day-study-limits.json")).use(mapper::readTree)
        Validation.buildDefaultValidatorFactory().use { factory ->
            fixtures.forEach { fixture ->
                val start = OffsetDateTime.parse(fixture["start"].asText())
                val duration = mapper.treeToValue(fixture["duration"], StudyDuration::class.java)
                Mockito.mockStatic(OffsetDateTime::class.java, Mockito.CALLS_REAL_METHODS).use { clock ->
                    clock.`when`<OffsetDateTime> { OffsetDateTime.now() }.thenReturn(start)
                    val limits = StudyLimits(studyDuration = duration, dataRetentionDuration = duration)
                    assertTrue(factory.validator.validate(limits).isEmpty())
                    assertEquals(OffsetDateTime.parse(fixture["studyEnds"].asText()), limits.studyEnds)
                    assertEquals(OffsetDateTime.parse(fixture["studyDataExpires"].asText()), limits.studyDataExpires)
                }
                val serialization = DefaultSerializationServiceBuilder().build()
                try {
                    val output = serialization.createObjectDataOutput(16)
                    StudyLimitsStreamSerializer.serializeStudyDuration(output, duration)
                    val input = serialization.createObjectDataInput(output.toByteArray())
                    assertEquals(duration, StudyLimitsStreamSerializer.deserializeStudyDuration(input))
                } finally {
                    serialization.dispose()
                }
            }
            for (days in listOf(-1, StudyDuration.MAX_EXACT_DAYS.toInt() + 1)) {
                assertFalse(factory.validator.validate(StudyLimits(studyDuration = StudyDuration(days = days))).isEmpty())
            }
        }
    }
}
