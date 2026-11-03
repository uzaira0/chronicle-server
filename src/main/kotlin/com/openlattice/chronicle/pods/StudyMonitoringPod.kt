package com.openlattice.chronicle.pods

import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.services.notifications.NotificationService
import com.openlattice.chronicle.services.studies.StudyComplianceService
import com.openlattice.chronicle.services.studies.StudyLimitsManager
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.studies.tasks.StudyComplianceHazelcastTask
import com.openlattice.chronicle.services.studies.tasks.StudyComplianceHazelcastTaskDependencies
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.studies.tasks.StudyLimitsEnforcementTask
import com.openlattice.chronicle.studies.tasks.StudyLimitsEnforcementTaskDependencies
import com.openlattice.chronicle.study.StudyComplianceManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
public open class StudyMonitoringPod(
    private val storageResolver: StorageResolver,
    private val hazelcast: HazelcastInstance,
) {

    @Bean
    public fun studyLimitsEnforcementTask(): StudyLimitsEnforcementTask {
        return StudyLimitsEnforcementTask()
    }

    @Bean
    public fun studyLimitsEnforcementTaskDependencies(
        studyService: StudyService,
        studyLimitsManager: StudyLimitsManager,
    ): StudyLimitsEnforcementTaskDependencies {
        return StudyLimitsEnforcementTaskDependencies(
            storageResolver,
            studyLimitsManager,
            studyService
        )
    }

    @Bean
    public fun studyComplianceManager(
        auditingManager: AuditingManager,
    ): StudyComplianceManager {
        return StudyComplianceService(storageResolver, auditingManager, hazelcast)
    }

    @Bean
    public fun studyComplianceTask(): StudyComplianceHazelcastTask {
        return StudyComplianceHazelcastTask()
    }

    @Bean
    public fun studyComplianceTaskDependencies(
        studyService: StudyService,
        notificationService: NotificationService,
        studyComplianceManager: StudyComplianceManager,
    ): StudyComplianceHazelcastTaskDependencies {
        return StudyComplianceHazelcastTaskDependencies(
            studyComplianceManager,
            studyService,
            storageResolver,
            notificationService
        )
    }
}
