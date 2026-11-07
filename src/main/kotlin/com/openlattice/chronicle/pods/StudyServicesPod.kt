package com.openlattice.chronicle.pods

import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.configuration.TwilioConfiguration
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.mapstores.stats.HazelcastParticipantStatsCache
import com.openlattice.chronicle.mapstores.stats.ParticipantStatsCache
import com.openlattice.chronicle.services.ScheduledTasksManager
import com.openlattice.chronicle.services.candidates.CandidateService
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.studies.ParticipantProcessingService
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.enrollment.EnrollmentService
import com.openlattice.chronicle.services.jobs.JobService
import com.openlattice.chronicle.services.notifications.NotificationService
import com.openlattice.chronicle.services.studies.ParticipantCollectionAcknowledgmentService
import com.openlattice.chronicle.services.studies.StudyDeletionScheduler
import com.openlattice.chronicle.services.studies.StudyLifecycleService
import com.openlattice.chronicle.services.studies.StudyLimitsManager
import com.openlattice.chronicle.services.studies.StudyLimitsService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.studies.StudySettingsAuditService
import com.openlattice.chronicle.services.studies.StudySettingsNotificationService
import com.openlattice.chronicle.services.surveys.SurveysManager
import com.openlattice.chronicle.services.surveys.SurveysService
import com.openlattice.chronicle.services.timeusediary.TimeUseDiaryService
import com.openlattice.chronicle.services.twilio.TwilioService
import com.openlattice.chronicle.services.twilio.TwilioWebhookSignatureVerifier
import com.openlattice.chronicle.configuration.ChronicleConfiguration
import com.openlattice.chronicle.services.webhooks.WebhookService
import com.openlattice.chronicle.storage.StorageResolver
import java.io.IOException
import java.util.concurrent.ExecutionException
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
public open class StudyServicesPod(
    private val storageResolver: StorageResolver,
    private val idGenerationService: HazelcastIdGenerationService,
    private val authorizationService: AuthorizationManager,
    private val auditingManager: AuditingManager,
    private val hazelcast: HazelcastInstance,
) {

    @Bean
    @Throws(IOException::class, ExecutionException::class)
    public fun scheduledTasksManager(): ScheduledTasksManager {
        return ScheduledTasksManager(
            storageResolver
        )
    }

    @Bean
    public fun participantStatsCache(): ParticipantStatsCache =
        HazelcastParticipantStatsCache(storageResolver, hazelcast)

    @Bean
    public fun participantProcessingService(): ParticipantProcessingService =
        ParticipantProcessingService(storageResolver, auditingManager)

    @Bean
    public fun candidateService(
    ): CandidateService {
        return CandidateService(storageResolver, authorizationService, idGenerationService)
    }

    @Bean
    @Throws(IOException::class, ExecutionException::class)
    public fun enrollmentManager(
        candidateService: CandidateService,
    ): EnrollmentManager {
        return EnrollmentService(
            storageResolver,
            idGenerationService,
            candidateService,
        )
    }

    @Bean
    @Throws(IOException::class, ExecutionException::class)
    public fun surveysManager(
        enrollmentManager: EnrollmentManager,
        scheduledTasksManager: ScheduledTasksManager,
    ): SurveysManager {
        return SurveysService(
            hazelcast,
            storageResolver,
            enrollmentManager,
            scheduledTasksManager,
            auditingManager,
            idGenerationService,
        )
    }

    @Bean
    public fun studyLimitsManager(): StudyLimitsManager {
        return StudyLimitsService(storageResolver, hazelcast)
    }

    @Bean
    public fun webhookService(
        chronicleConfiguration: ChronicleConfiguration,
    ): WebhookService {
        return WebhookService(
            storageResolver,
            idGenerationService,
            settings = chronicleConfiguration.webhooks,
        )
    }

    @Bean
    public fun studyService(
        candidateService: CandidateService,
        enrollmentManager: EnrollmentManager,
        surveysManager: SurveysManager,
        studyLimitsManager: StudyLimitsManager,
        webhookService: WebhookService,
        participantStatsCache: ParticipantStatsCache,
    ): StudyService {
        return StudyService(
            storageResolver,
            authorizationService,
            candidateService,
            enrollmentManager,
            surveysManager,
            idGenerationService,
            studyLimitsManager,
            auditingManager,
            hazelcast,
            participantStatsCache,
            webhookService,
        )
    }

    @Bean
    public fun studySettingsAuditService(): StudySettingsAuditService {
        return StudySettingsAuditService(storageResolver)
    }

    @Bean
    public fun participantCollectionAcknowledgmentService(): ParticipantCollectionAcknowledgmentService {
        return ParticipantCollectionAcknowledgmentService(storageResolver)
    }

    @Bean
    public fun studySettingsNotificationService(): StudySettingsNotificationService {
        return StudySettingsNotificationService()
    }

    @Bean
    public fun studyLifecycleService(
        dataDeletionOrchestrator: DataDeletionOrchestrator,
        studyService: StudyService,
        webhookService: WebhookService,
        studyLimitsManager: StudyLimitsManager,
    ): StudyLifecycleService {
        return StudyLifecycleService(
            storageResolver,
            studyService,
            authorizationService,
            idGenerationService,
            auditingManager,
            dataDeletionOrchestrator,
            webhookService,
            studyLimitsManager,
        )
    }

    @Bean
    public fun studyDeletionScheduler(
        studyLifecycleService: StudyLifecycleService,
        dataDeletionOrchestrator: DataDeletionOrchestrator,
    ): StudyDeletionScheduler {
        return StudyDeletionScheduler(studyLifecycleService, dataDeletionOrchestrator)
    }

    @Bean
    public fun jobService(
    ): JobService {
        return JobService(
            idGenerationService,
            storageResolver,
            auditingManager
        )
    }

    @Bean
    public fun twilioService(
        twilioConfiguration: TwilioConfiguration,
        studyService: StudyService,
    ): TwilioService {
        return TwilioService(
            twilioConfiguration,
            studyService,
        )
    }

    @Bean
    public fun twilioWebhookSignatureVerifier(
        twilioConfiguration: TwilioConfiguration,
    ): TwilioWebhookSignatureVerifier {
        return TwilioWebhookSignatureVerifier(twilioConfiguration)
    }

    @Bean
    public fun notificationService(
        enrollmentManager: EnrollmentManager,
        candidateService: CandidateService,
        studyService: StudyService,
        jobService: JobService,
        twilioService: TwilioService,
    ): NotificationService {
        return NotificationService(
            storageResolver,
            authorizationService,
            enrollmentManager,
            candidateService,
            studyService,
            jobService,
            idGenerationService,
            twilioService,
            auditingManager,
        )
    }

    @Bean
    public fun timeUseDiaryService(
        studyService: StudyService,
    ): TimeUseDiaryService {
        return TimeUseDiaryService(storageResolver, idGenerationService, studyService)
    }
}
