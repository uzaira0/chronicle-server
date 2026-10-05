package com.openlattice.chronicle.services.surveys

import com.geekbeast.rhizome.KotlinDelegatedStringSet
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.android.ChronicleUsageEventType
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.services.ScheduledTasksManager
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.survey.AppUsage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class SurveysServiceAppUsageTest {
    @Test
    fun consecutivePauseEventsDoNotCountBackgroundTimeAsAppUsage() {
        val hazelcast = Mockito.mock(HazelcastInstance::class.java)
        @Suppress("UNCHECKED_CAST")
        val filteredApps = Mockito.mock(IMap::class.java) as IMap<UUID, KotlinDelegatedStringSet>
        Mockito.`when`(hazelcast.getMap<UUID, KotlinDelegatedStringSet>("FILTERED_APPS")).thenReturn(filteredApps)

        val service = SurveysService(
            hazelcast,
            Mockito.mock(StorageResolver::class.java),
            Mockito.mock(EnrollmentManager::class.java),
            Mockito.mock(ScheduledTasksManager::class.java),
            Mockito.mock(AuditingManager::class.java),
            Mockito.mock(HazelcastIdGenerationService::class.java),
        )
        val start = OffsetDateTime.of(2024, 1, 1, 9, 0, 0, 0, ZoneOffset.UTC)
        val appUsage = listOf(
            AppUsage(
                "example.app",
                "Example",
                start,
                ChronicleUsageEventType.ACTIVITY_RESUMED.value,
                timezone = "UTC",
                uploadedAt = Optional.empty(),
            ),
            AppUsage(
                "example.app",
                "Example",
                start.plusMinutes(10),
                ChronicleUsageEventType.ACTIVITY_PAUSED.value,
                timezone = "UTC",
                uploadedAt = Optional.empty(),
            ),
            AppUsage(
                "example.app",
                "Example",
                start.plusHours(1),
                ChronicleUsageEventType.ACTIVITY_PAUSED.value,
                timezone = "UTC",
                uploadedAt = Optional.empty(),
            ),
        )

        assertEquals(600.0, service.computeAggregateUsage(start, appUsage).getValue("example.app"), 0.0)
    }

    private fun service(): SurveysService {
        val hazelcast = Mockito.mock(HazelcastInstance::class.java)
        @Suppress("UNCHECKED_CAST")
        val filteredApps = Mockito.mock(IMap::class.java) as IMap<UUID, KotlinDelegatedStringSet>
        Mockito.`when`(hazelcast.getMap<UUID, KotlinDelegatedStringSet>("FILTERED_APPS")).thenReturn(filteredApps)
        return SurveysService(
            hazelcast,
            Mockito.mock(StorageResolver::class.java),
            Mockito.mock(EnrollmentManager::class.java),
            Mockito.mock(ScheduledTasksManager::class.java),
            Mockito.mock(AuditingManager::class.java),
            Mockito.mock(HazelcastIdGenerationService::class.java),
        )
    }

    private fun event(at: OffsetDateTime, type: ChronicleUsageEventType) =
        AppUsage("example.app", "Example", at, type.value, timezone = "UTC", uploadedAt = Optional.empty())

    // Regression V-3: a resume whose pause was lost (power loss) kept the interval anchored at the
    // first resume, so the next pause counted the whole four-hour gap.
    @Test
    fun resumeWithMissingPauseReanchorsAtTheNextResume() {
        val start = OffsetDateTime.of(2024, 1, 1, 14, 0, 0, 0, ZoneOffset.UTC)
        val appUsage = listOf(
            event(start, ChronicleUsageEventType.ACTIVITY_RESUMED),
            event(start.plusHours(4), ChronicleUsageEventType.ACTIVITY_RESUMED),
            event(start.plusHours(4).plusMinutes(5), ChronicleUsageEventType.ACTIVITY_PAUSED),
        )

        assertEquals(300.0, service().computeAggregateUsage(start, appUsage).getValue("example.app"), 0.0)
    }

    // Regression V-11: two activities already resumed at the window boundary pause at 09:10 and
    // 09:20; only the first leading pause was counted (600 s instead of 1,200 s).
    @Test
    fun leadingPausesCountAsTheUnionFromTheWindowStart() {
        val start = OffsetDateTime.of(2024, 1, 1, 9, 0, 0, 0, ZoneOffset.UTC)
        val appUsage = listOf(
            event(start.plusMinutes(10), ChronicleUsageEventType.ACTIVITY_PAUSED),
            event(start.plusMinutes(20), ChronicleUsageEventType.ACTIVITY_PAUSED),
            event(start.plusMinutes(30), ChronicleUsageEventType.ACTIVITY_RESUMED),
            event(start.plusMinutes(35), ChronicleUsageEventType.ACTIVITY_PAUSED),
            event(start.plusMinutes(50), ChronicleUsageEventType.ACTIVITY_PAUSED),
        )

        assertEquals(1500.0, service().computeAggregateUsage(start, appUsage).getValue("example.app"), 0.0)
    }
}
