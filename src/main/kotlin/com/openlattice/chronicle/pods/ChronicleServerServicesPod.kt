/*
 * Copyright (C) 2018. OpenLattice, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * You can contact the owner of the copyright at support@openlattice.com
 *
 */
package com.openlattice.chronicle.pods

import com.fasterxml.jackson.databind.ObjectMapper
import com.geekbeast.hazelcast.HazelcastClientProvider
import com.google.common.eventbus.EventBus
import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.auditing.PostgresAuditingManager
import com.openlattice.chronicle.audit.AuditLogRepository
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.HazelcastAuthorizationService
import com.openlattice.chronicle.authorization.initializers.AuthorizationInitializationDependencies
import com.openlattice.chronicle.authorization.initializers.AuthorizationInitializationTask
import com.openlattice.chronicle.authorization.principals.HazelcastPrincipalService
import com.openlattice.chronicle.authorization.principals.HazelcastPrincipalsMapManager
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.authorization.principals.PrincipalsMapManager
import com.openlattice.chronicle.authorization.principals.SecurePrincipalsManager
import com.openlattice.chronicle.authorization.reservations.AclKeyReservationService
import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.configuration.ChronicleConfiguration
import com.openlattice.chronicle.configuration.TwilioConfiguration
import com.openlattice.chronicle.directory.ConfiguredUserDirectoryService
import com.openlattice.chronicle.directory.UserDirectoryService
import com.openlattice.chronicle.ids.HazelcastIdGenerationService
import com.openlattice.chronicle.mapstores.stats.HazelcastParticipantStatsCache
import com.openlattice.chronicle.mapstores.stats.ParticipantStatsCache
import com.openlattice.chronicle.organizations.ChronicleOrganizationService
import com.openlattice.chronicle.organizations.initializers.OrganizationsInitializationDependencies
import com.openlattice.chronicle.organizations.initializers.OrganizationsInitializationTask
import com.openlattice.chronicle.services.ScheduledTasksManager
import com.openlattice.chronicle.services.security.SecretRotationService
import com.openlattice.chronicle.services.candidates.CandidateService
import com.openlattice.chronicle.services.delete.DataDeletionManager
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.delete.DataDeletionService
import com.openlattice.chronicle.services.delete.ParticipantPurgeService
import com.openlattice.chronicle.services.delete.RestoreContinuityReconciler
import com.openlattice.chronicle.services.quality.DataQualityService
import com.openlattice.chronicle.services.roles.RoleService
import com.openlattice.chronicle.services.download.DataDownloadManager
import com.openlattice.chronicle.services.download.DataDownloadService
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.enrollment.EnrollmentService
import com.openlattice.chronicle.services.jobs.JobService
import com.openlattice.chronicle.services.notifications.NotificationService
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.services.participantaccess.ParticipantFormSubmissionReceiptService
import com.openlattice.chronicle.services.settings.OrganizationSettingsManager
import com.openlattice.chronicle.services.settings.OrganizationSettingsService
import com.openlattice.chronicle.services.studies.ParticipantCollectionAcknowledgmentService
import com.openlattice.chronicle.services.studies.StudyComplianceService
import com.openlattice.chronicle.services.studies.StudyDeletionScheduler
import com.openlattice.chronicle.services.studies.StudyLifecycleService
import com.openlattice.chronicle.services.studies.StudyLimitsManager
import com.openlattice.chronicle.services.studies.StudyLimitsService
import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.studies.StudySettingsAuditService
import com.openlattice.chronicle.services.studies.StudySettingsNotificationService
import com.openlattice.chronicle.services.studies.tasks.StudyComplianceHazelcastTask
import com.openlattice.chronicle.services.studies.tasks.StudyComplianceHazelcastTaskDependencies
import com.openlattice.chronicle.services.surveys.SurveysManager
import com.openlattice.chronicle.services.surveys.SurveysService
import com.openlattice.chronicle.services.timeusediary.TimeUseDiaryService
import com.openlattice.chronicle.services.twilio.TwilioService
import com.openlattice.chronicle.services.twilio.TwilioWebhookSignatureVerifier
import com.openlattice.chronicle.services.security.EncryptionHealthService
import com.openlattice.chronicle.services.upload.AndroidSensorDataUploadService
import com.openlattice.chronicle.services.upload.BatteryTelemetryUploadService
import com.openlattice.chronicle.services.upload.InteractionEventsUploadService
import com.openlattice.chronicle.services.upload.AmbientAudioUploadService
import com.openlattice.chronicle.services.upload.AppAudioActivityUploadService
import com.openlattice.chronicle.services.upload.AppAudioContentUploadService
import com.openlattice.chronicle.services.upload.NotificationActivityUploadService
import com.openlattice.chronicle.services.upload.SleepEventsUploadService
import com.openlattice.chronicle.services.upload.ActivityRecognitionEventsUploadService
import com.openlattice.chronicle.services.upload.HealthMetricsUploadService
import com.openlattice.chronicle.services.upload.ConnectivityStateEventsUploadService
import com.openlattice.chronicle.services.upload.AppNetworkUsageUploadService
import com.openlattice.chronicle.services.upload.DeviceSettingsUploadService
import com.openlattice.chronicle.services.upload.UploadDiagnosticsUploadService
import com.openlattice.chronicle.services.upload.EncryptedPayloadUploadService
import com.openlattice.chronicle.services.crypto.EnvelopeDecryptionService
import com.openlattice.chronicle.services.crypto.FileStudyKeyStore
import com.openlattice.chronicle.services.crypto.StudyEncryptionKeyService
import com.openlattice.chronicle.services.crypto.StudyKeyStore
import com.openlattice.chronicle.services.crypto.VaultStudyKeyStore
import com.openlattice.chronicle.configuration.VaultSecretProvider
import java.nio.file.Paths
import com.openlattice.chronicle.services.upload.AppDataUploadManager
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.services.upload.SensorDataUploadService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.tasks.MoveAndroidSensorDataToStorageTask
import com.openlattice.chronicle.storage.tasks.MoveToEventStorageTask
import com.openlattice.chronicle.storage.tasks.MoveToEventStorageTaskDependencies
import com.openlattice.chronicle.storage.tasks.MoveToIosEventStorageTask
import com.openlattice.chronicle.storage.tasks.RecalculateParticipantStatsTask
import com.openlattice.chronicle.storage.tasks.RecalculateParticipantStatsTaskDependencies
import com.openlattice.chronicle.studies.tasks.StudyLimitsEnforcementTask
import com.openlattice.chronicle.studies.tasks.StudyLimitsEnforcementTaskDependencies
import com.openlattice.chronicle.study.StudyComplianceManager
import com.openlattice.chronicle.tasks.PostConstructInitializerTaskDependencies
import com.openlattice.chronicle.tasks.RestoreContinuityInitializationDependencies
import com.openlattice.chronicle.tasks.RestoreContinuityInitializationTask
import com.openlattice.chronicle.users.ConfiguredUserListingService
import com.openlattice.chronicle.users.UserListingService
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import java.io.IOException
import java.util.concurrent.ExecutionException
import jakarta.annotation.PostConstruct
import jakarta.inject.Inject

@Configuration
@Import(
    com.openlattice.chronicle.configuration.JwtKeyConfig::class,
    com.openlattice.chronicle.configuration.JacksonSecurityConfig::class,
    com.openlattice.chronicle.configuration.SecurityHardeningConfig::class,
    com.openlattice.chronicle.configuration.CorsValidationFilterConfig::class,
    com.openlattice.chronicle.configuration.MobileApiSecurityConfig::class,
    com.openlattice.chronicle.configuration.RateLimitConfig::class,
)
// reason: Spring DI pod — each function is a @Bean factory wiring one service; this is the central
// dependency-injection registry and splitting it would fragment the application's bean graph
@Suppress("TooManyFunctions")
public open class ChronicleServerServicesPod {
    @Inject
    private lateinit var chronicleAuthConfiguration: ChronicleAuthConfiguration

    @Inject
    private lateinit var jwtKeyMaterial: com.openlattice.chronicle.configuration.JwtKeyMaterial

    @Value("\${chronicle.security.jwt.access-token-expiry-minutes:15}")
    private var accessTokenExpiryMinutes: Long = 15

    @Value("\${chronicle.security.jwt.refresh-token-expiry-days:7}")
    private var refreshTokenExpiryDays: Long = 7

    @Value("\${chronicle.security.require-mfa:true}")
    private var requireMfa: Boolean = true

    @Inject
    private lateinit var hazelcastClientProvider: HazelcastClientProvider

    @Inject
    private lateinit var hazelcast: HazelcastInstance

    @Inject
    private lateinit var eventBus: EventBus

    @Inject
    private lateinit var chronicleConfiguration: ChronicleConfiguration

    @Inject
    private lateinit var storageResolver: StorageResolver

    @Inject
    private lateinit var vaultSecretProvider: VaultSecretProvider

    // Directory holding per-study RSA private keys when Vault is disabled (dev/test, or a
    // secret-mounted volume). Mirrors the JWT key-file fallback. Ignored when Vault is available.
    @Value("\${chronicle.security.encryption.key-dir:/app/encryption-keys}")
    private lateinit var encryptionKeyDir: String

    @Inject
    private lateinit var twilioConfiguration: TwilioConfiguration

    // Secure ObjectMapper provided by JacksonSecurityConfig with security hardening
    @Inject
    private lateinit var secureObjectMapper: ObjectMapper

    @Bean
    public fun jwtBlocklist(): com.openlattice.chronicle.authorization.JwtBlocklist {
        return com.openlattice.chronicle.authorization.JwtBlocklist(hazelcast)
    }

    @Bean
    @Throws(IOException::class, ExecutionException::class)
    public fun scheduledTasksManager(): ScheduledTasksManager {
        return ScheduledTasksManager(
            storageResolver
        )
    }

    @Bean
    @Throws(IOException::class, ExecutionException::class)
    public fun dataDeletionManager(): DataDeletionManager {
        return DataDeletionService(enrollmentManager())
    }

    @Bean
    public fun participantStatsCache(): ParticipantStatsCache =
        HazelcastParticipantStatsCache(storageResolver, hazelcast)

    @Bean
    public fun dataDeletionOrchestrator(): DataDeletionOrchestrator =
        DataDeletionOrchestrator(
            storageResolver,
            auditingManager(),
            participantStatsCache(),
        )

    @Bean
    public fun restoreContinuityReconciler(): RestoreContinuityReconciler =
        RestoreContinuityReconciler(storageResolver, dataDeletionOrchestrator())

    @Bean
    public fun restoreContinuityInitializationDependencies(): RestoreContinuityInitializationDependencies =
        RestoreContinuityInitializationDependencies(restoreContinuityReconciler())

    @Bean
    public fun restoreContinuityInitializationTask(): RestoreContinuityInitializationTask =
        RestoreContinuityInitializationTask()

    @Bean
    public fun participantPurgeService(): ParticipantPurgeService {
        return ParticipantPurgeService(
            storageResolver,
            dataDeletionOrchestrator(),
            apiKeyService(),
            auditingManager(),
        )
    }

    @Bean
    public fun participantFormAccessService(): ParticipantFormAccessService =
        ParticipantFormAccessService(storageResolver)

    @Bean
    public fun participantFormSubmissionReceiptService(): ParticipantFormSubmissionReceiptService =
        ParticipantFormSubmissionReceiptService(storageResolver, secureObjectMapper)

    @Bean
    public fun dataQualityService(): DataQualityService {
        return DataQualityService(
            storageResolver,
            studyService(),
        )
    }

    @Bean
    public fun dataQualityScheduler(): com.openlattice.chronicle.services.quality.DataQualityScheduler {
        return com.openlattice.chronicle.services.quality.DataQualityScheduler(
            studyService(),
            dataQualityService(),
        )
    }

    @Bean
    public fun roleService(): RoleService {
        return RoleService(authorizationService(), auditingManager(), hazelcast)
    }

    @Bean
    public fun pipelineService(): com.openlattice.chronicle.pipeline.PipelineService {
        return com.openlattice.chronicle.pipeline.PipelineService(
            storageResolver,
            jobService(),
            idGenerationService(),
            studyService(),
            auditingManager(),
        )
    }

    @Bean
    @Throws(IOException::class, ExecutionException::class)
    public fun dataDownloadManager(): DataDownloadManager {
        return DataDownloadService(storageResolver)
    }

    @Bean
    @Throws(IOException::class, ExecutionException::class)
    public fun enrollmentManager(): EnrollmentManager {
        return EnrollmentService(
            storageResolver,
            idGenerationService(),
            candidateService(),
        )
    }


    @Bean
    public fun organizationSettingsManager(): OrganizationSettingsManager {
        return OrganizationSettingsService(storageResolver)
    }

    @Bean
    @Throws(IOException::class, ExecutionException::class)
    public fun appDataUploadManager(): AppDataUploadManager {
        return AppDataUploadService(
            storageResolver,
            enrollmentManager(),
            studyService()
        )
    }

    @Bean
    @Throws(IOException::class, ExecutionException::class)
    public fun surveysManager(): SurveysManager {
        return SurveysService(
            hazelcast,
            storageResolver,
            enrollmentManager(),
            scheduledTasksManager(),
            auditingManager(),
            idGenerationService(),
        )
    }

    @Bean
    public fun postConstructInitializerTaskDependencies(): PostConstructInitializerTaskDependencies {
        return PostConstructInitializerTaskDependencies()
    }

    @Bean
    public fun postInitializerTask(): PostConstructInitializerTaskDependencies.PostConstructInitializerTask {
        return PostConstructInitializerTaskDependencies.PostConstructInitializerTask()
    }

    @Bean
    public fun idGenerationService(): HazelcastIdGenerationService {
        return HazelcastIdGenerationService(hazelcastClientProvider)
    }

    @Bean
    public fun principalsMapManager(): PrincipalsMapManager {
        return HazelcastPrincipalsMapManager(hazelcast, aclKeyReservationService())
    }

    @Bean
    public fun aclKeyReservationService(): AclKeyReservationService {
        return AclKeyReservationService(storageResolver)
    }

    @Bean
    public fun authorizationService(): AuthorizationManager {
        return HazelcastAuthorizationService(hazelcast, storageResolver, eventBus, principalsMapManager())
    }

    @Bean
    public fun principalsManager(): SecurePrincipalsManager {
        return HazelcastPrincipalService(
            hazelcast,
            aclKeyReservationService(),
            authorizationService(),
            principalsMapManager(),
            auditingManager()
        )
    }

    @Bean
    public fun userListingService(): UserListingService {
        return ConfiguredUserListingService(
            chronicleAuthConfiguration,
            jwtKeyMaterial,
            accessTokenExpiryMinutes,
        )
    }

    @Bean
    public fun refreshTokenService(): com.openlattice.chronicle.services.auth.RefreshTokenService {
        val authConfig = chronicleAuthConfiguration.configurations.firstOrNull()
            ?: error("Chronicle auth configuration is missing JWT client settings.")

        // Populate HMAC secret into key material for HS256 token signing
        val effectiveKeyMaterial = if (jwtKeyMaterial.isHs256()) {
            val secret = if (authConfig.base64EncodedSecret) {
                java.util.Base64.getUrlDecoder().decode(authConfig.secret)
            } else {
                authConfig.secret.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            }
            jwtKeyMaterial.copy(hmacSecret = secret)
        } else {
            jwtKeyMaterial
        }

        return com.openlattice.chronicle.services.auth.RefreshTokenService(
            storageResolver = storageResolver,
            jwtKeyMaterial = effectiveKeyMaterial,
            issuer = authConfig.issuer,
            audience = authConfig.audience,
            accessTokenExpiryMinutes = accessTokenExpiryMinutes,
            refreshTokenExpiryDays = refreshTokenExpiryDays,
            requireMfa = requireMfa,
        )
    }

    @Bean
    public fun userDirectoryService(): UserDirectoryService {
        return ConfiguredUserDirectoryService(chronicleAuthConfiguration)
    }

    @Bean
    public fun jobService(): JobService {
        return JobService(
            idGenerationService(),
            storageResolver,
            auditingManager()
        )
    }

    @Bean
    public fun studyService(): StudyService {
        return StudyService(
            storageResolver,
            authorizationService(),
            candidateService(),
            enrollmentManager(),
            surveysManager(),
            idGenerationService(),
            studyLimitsManager(),
            auditingManager(),
            hazelcast,
            participantStatsCache(),
            webhookService(),
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
        storageResolver: StorageResolver,
        studyService: StudyService,
        authorizationService: AuthorizationManager,
        idGenerationService: HazelcastIdGenerationService,
        auditingManager: AuditingManager,
        dataDeletionOrchestrator: DataDeletionOrchestrator,
    ): StudyLifecycleService {
        return StudyLifecycleService(
            storageResolver,
            studyService,
            authorizationService,
            idGenerationService,
            auditingManager,
            dataDeletionOrchestrator,
            webhookService(),
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
    public fun apiKeyService(): com.openlattice.chronicle.services.apikeys.ApiKeyService {
        return com.openlattice.chronicle.services.apikeys.ApiKeyService(
            storageResolver,
            idGenerationService(),
            auditingManager()
        )
    }

    @Bean
    public fun anonymizationService(): com.openlattice.chronicle.services.anonymization.AnonymizationService {
        return com.openlattice.chronicle.services.anonymization.AnonymizationService(storageResolver)
    }

    @Bean
    public fun organizationMemberService(): com.openlattice.chronicle.services.organizations.OrganizationMemberService {
        return com.openlattice.chronicle.services.organizations.OrganizationMemberService(storageResolver)
    }

    @Bean
    public fun dashboardService(): com.openlattice.chronicle.services.dashboard.DashboardService {
        return com.openlattice.chronicle.services.dashboard.DashboardService(storageResolver)
    }

    @Bean
    public fun webhookService(): com.openlattice.chronicle.services.webhooks.WebhookService {
        return com.openlattice.chronicle.services.webhooks.WebhookService(
            storageResolver,
            idGenerationService()
        )
    }

    @Bean
    public fun exportService(): com.openlattice.chronicle.services.export.ExportService {
        return com.openlattice.chronicle.services.export.ExportService(
            storageResolver,
            dataDownloadManager(),
            idGenerationService(),
            webhookService(),
        )
    }

    @Bean
    public fun exportFileCleanupScheduler(): com.openlattice.chronicle.services.export.ExportFileCleanupScheduler {
        return com.openlattice.chronicle.services.export.ExportFileCleanupScheduler(exportService())
    }

    @Bean
    public fun twilioService(): TwilioService {
        return TwilioService(
            twilioConfiguration,
            studyService()
        )
    }

    @Bean
    public fun twilioWebhookSignatureVerifier(): TwilioWebhookSignatureVerifier {
        return TwilioWebhookSignatureVerifier(twilioConfiguration)
    }

    @Bean
    public fun notificationService(): NotificationService {
        return NotificationService(
            storageResolver,
            authorizationService(),
            enrollmentManager(),
            candidateService(),
            studyService(),
            jobService(),
            idGenerationService(),
            twilioService(),
            auditingManager(),
        )
    }

    @Bean
    public fun timeUseDiaryService(): TimeUseDiaryService {
        return TimeUseDiaryService(storageResolver, idGenerationService(), studyService())
    }

    @Bean
    public fun auditingManager(): AuditingManager {
        return PostgresAuditingManager(storageResolver)
    }

    /**
     * Repository for persisting audit log entries to PostgreSQL.
     * This provides the database storage component of the comprehensive audit system.
     */
    @Bean
    public fun auditLogRepository(): AuditLogRepository {
        return AuditLogRepository(storageResolver, secureObjectMapper)
    }

    /**
     * Comprehensive audit service that writes to BOTH database and log files.
     * Critical for HIPAA compliance - tracks all PHI access with who, what, when, outcome.
     *
     * Features:
     * - Dual-write: Every audit event goes to both database and log file
     * - Async processing: Non-blocking to avoid impacting request performance
     * - JSON format: Compatible with SIEM tools (Splunk, ELK, DataDog, etc.)
     */
    @Bean
    public fun auditService(): AuditService {
        return AuditService(auditLogRepository(), secureObjectMapper)
    }

    @Bean
    public fun organizationsService(): ChronicleOrganizationService {
        return ChronicleOrganizationService(
            storageResolver,
            authorizationService(),
            idGenerationService(),
            auditingManager()
        )
    }

    @Bean
    public fun authorizationInitializationTask(): AuthorizationInitializationTask {
        return AuthorizationInitializationTask()
    }

    @Bean
    public fun authorizationInitializationTaskDependencies(): AuthorizationInitializationDependencies {
        return AuthorizationInitializationDependencies(principalsManager())
    }

    @Bean
    public fun organizationInitTask(): OrganizationsInitializationTask {
        return OrganizationsInitializationTask()
    }

    @Bean
    public fun orgInitTaskDependencies(): OrganizationsInitializationDependencies {
        return OrganizationsInitializationDependencies(
            storageResolver,
            organizationsService(),
            principalsManager(),
            chronicleConfiguration
        )
    }

    @Bean
    public fun candidateService(): CandidateService {
        return CandidateService(storageResolver, authorizationService(), idGenerationService())
    }

    @Bean
    public fun sensorDataUploadService(): SensorDataUploadService {
        return SensorDataUploadService(storageResolver, studyService())
    }

    @Bean
    public fun androidSensorDataUploadService(): AndroidSensorDataUploadService {
        return AndroidSensorDataUploadService(storageResolver)
    }

    @Bean
    public fun batteryTelemetryUploadService(): BatteryTelemetryUploadService {
        return BatteryTelemetryUploadService(storageResolver)
    }

    @Bean
    public fun interactionEventsUploadService(): InteractionEventsUploadService {
        return InteractionEventsUploadService(storageResolver)
    }

    @Bean
    public fun appAudioActivityUploadService(): AppAudioActivityUploadService {
        return AppAudioActivityUploadService(storageResolver)
    }

    @Bean
    public fun ambientAudioUploadService(): AmbientAudioUploadService {
        return AmbientAudioUploadService(storageResolver)
    }

    @Bean
    public fun appAudioContentUploadService(): AppAudioContentUploadService {
        return AppAudioContentUploadService(storageResolver)
    }

    @Bean
    public fun notificationActivityUploadService(): NotificationActivityUploadService {
        return NotificationActivityUploadService(storageResolver)
    }

    @Bean
    public fun sleepEventsUploadService(): SleepEventsUploadService {
        return SleepEventsUploadService(storageResolver)
    }

    @Bean
    public fun activityRecognitionEventsUploadService(): ActivityRecognitionEventsUploadService {
        return ActivityRecognitionEventsUploadService(storageResolver)
    }

    @Bean
    public fun healthMetricsUploadService(): HealthMetricsUploadService {
        return HealthMetricsUploadService(storageResolver)
    }

    @Bean
    public fun connectivityStateEventsUploadService(): ConnectivityStateEventsUploadService {
        return ConnectivityStateEventsUploadService(storageResolver)
    }

    @Bean
    public fun appNetworkUsageUploadService(): AppNetworkUsageUploadService {
        return AppNetworkUsageUploadService(storageResolver)
    }

    @Bean
    public fun deviceSettingsUploadService(): DeviceSettingsUploadService {
        return DeviceSettingsUploadService(storageResolver)
    }

    @Bean
    public fun uploadDiagnosticsUploadService(): UploadDiagnosticsUploadService {
        return UploadDiagnosticsUploadService(storageResolver)
    }

    @Bean
    public fun encryptionHealthService(): EncryptionHealthService {
        return EncryptionHealthService(storageResolver)
    }

    // ── Envelope encryption of Android payloads (HIPAA-2028 W2) ──────────────────
    @Bean
    public fun studyKeyStore(): StudyKeyStore {
        return if (vaultSecretProvider.isAvailable()) {
            VaultStudyKeyStore(vaultSecretProvider)
        } else {
            FileStudyKeyStore(Paths.get(encryptionKeyDir))
        }
    }

    @Bean
    public fun encryptedPayloadUploadService(): EncryptedPayloadUploadService {
        return EncryptedPayloadUploadService(storageResolver)
    }

    @Bean
    public fun envelopeDecryptionService(): EnvelopeDecryptionService {
        return EnvelopeDecryptionService(storageResolver, studyKeyStore(), authorizationService())
    }

    @Bean
    public fun studyEncryptionKeyService(): StudyEncryptionKeyService {
        return StudyEncryptionKeyService(studyKeyStore())
    }

    /**
     * Tracks rotation status of JWT, HMAC, API keys, and TLS certs.
     * Logs warnings on startup if any secret has not been rotated in >90 days.
     * Exposes GET /internal/health/secrets for monitoring.
     */
    @Bean
    public fun secretRotationService(): SecretRotationService {
        return SecretRotationService(storageResolver)
    }

    @Bean
    public fun studyLimitsManager(): StudyLimitsManager {
        return StudyLimitsService(storageResolver, hazelcast)
    }

    @Bean
    public fun studyLimitsEnforcementTask(): StudyLimitsEnforcementTask {
        return StudyLimitsEnforcementTask()
    }

    @Bean
    public fun studyLimitsEnforcementTaskDependencies(): StudyLimitsEnforcementTaskDependencies {
        return StudyLimitsEnforcementTaskDependencies(
            storageResolver,
            studyLimitsManager(),
            studyService()
        )
    }

    @Bean
    public fun studyComplianceManager(): StudyComplianceManager {
        return StudyComplianceService(storageResolver, auditingManager(), hazelcast)
    }

    @Bean
    public fun studyComplianceTask(): StudyComplianceHazelcastTask {
        return StudyComplianceHazelcastTask()
    }

    @Bean
    public fun studyComplianceTaskDependencies(): StudyComplianceHazelcastTaskDependencies {
        return StudyComplianceHazelcastTaskDependencies(
            studyComplianceManager(),
            studyService(),
            storageResolver,
            notificationService()
        )
    }

    @Bean
    public fun moveToEventStorageTaskDependencies(): MoveToEventStorageTaskDependencies {
        return MoveToEventStorageTaskDependencies(storageResolver, studyService())
    }

    @Bean
    public fun moveIosDataToEventStorageTaskDependencies(): MoveToIosEventStorageTask {
        return MoveToIosEventStorageTask()
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
    public fun recalculateParticipantStatsTaskDependencies(): RecalculateParticipantStatsTaskDependencies {
        return RecalculateParticipantStatsTaskDependencies(storageResolver, studyService())
    }

    @Bean
    public fun recalculateParticipantStatsTask(): RecalculateParticipantStatsTask {
        return RecalculateParticipantStatsTask()
    }

    @PostConstruct
    public fun init() {
        Principals.init(principalsManager(), hazelcast)
        storageResolver.setStudyStorage(hazelcast)
    }
}
