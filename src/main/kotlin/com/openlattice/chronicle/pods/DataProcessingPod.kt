package com.openlattice.chronicle.pods

import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.tasks.MoveAndroidSensorDataToStorageTask
import com.openlattice.chronicle.storage.tasks.MoveToEventStorageTask
import com.openlattice.chronicle.storage.tasks.MoveToEventStorageTaskDependencies
import com.openlattice.chronicle.storage.tasks.RecalculateParticipantStatsTask
import com.openlattice.chronicle.storage.tasks.RecalculateParticipantStatsTaskDependencies
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
public open class DataProcessingPod(
    private val storageResolver: StorageResolver,
) {

    @Bean
    public fun moveToEventStorageTaskDependencies(
        studyService: StudyService,
    ): MoveToEventStorageTaskDependencies {
        return MoveToEventStorageTaskDependencies(storageResolver, studyService)
    }

    @Bean
    public fun moveToEventStorageTask(): MoveToEventStorageTask {
        return MoveToEventStorageTask()
    }

    @Bean
    public fun moveAndroidSensorDataToStorageTask(): MoveAndroidSensorDataToStorageTask {
        return MoveAndroidSensorDataToStorageTask()
    }

    @Bean
    public fun recalculateParticipantStatsTaskDependencies(
        studyService: StudyService,
    ): RecalculateParticipantStatsTaskDependencies {
        return RecalculateParticipantStatsTaskDependencies(storageResolver, studyService)
    }

    @Bean
    public fun recalculateParticipantStatsTask(): RecalculateParticipantStatsTask {
        return RecalculateParticipantStatsTask()
    }
}
