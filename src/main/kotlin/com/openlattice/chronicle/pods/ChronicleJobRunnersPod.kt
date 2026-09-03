package com.openlattice.chronicle.pods

import com.openlattice.chronicle.deletion.DeleteParticipantActivityRecognitionEventsDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantAndroidSensorDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantAmbientAudioDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantAppAudioActivityDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantAppAudioContentDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantAppNetworkUsageDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantAppUsageSurveyDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantBatteryTelemetryDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantConnectivityStateEventsDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantDeviceSettingsDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantHealthMetricsDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantInteractionEventsDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantNotificationActivityDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantPreprocessedUsageDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantQuestionnaireSubmissionDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantRegisteredAssetDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantSensorDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantSleepEventsDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantStatsDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantTUDSubmissionDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantUploadBufferDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantUsageDataRunner
import com.openlattice.chronicle.deletion.DeleteParticipantUsageStatsDataRunner
import com.openlattice.chronicle.deletion.DeleteStudyAppUsageSurveyDataRunner
import com.openlattice.chronicle.deletion.DeleteStudyTUDSubmissionDataRunner
import com.openlattice.chronicle.deletion.DeleteStudyTableDataRunner
import com.openlattice.chronicle.deletion.DeleteStudyUsageDataRunner
import com.openlattice.chronicle.services.notifications.NotificationJobRunner
import com.openlattice.chronicle.services.twilio.TwilioService
import com.openlattice.chronicle.storage.StorageResolver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import jakarta.inject.Inject

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
// reason: Spring DI pod — one @Bean factory method per job runner; consolidating beans would obscure the DI wiring
@Suppress("TooManyFunctions")
@Configuration
public open class ChronicleJobRunnersPod {
        @Inject
        private lateinit var storageResolver: StorageResolver

        @Inject
        private lateinit var twilioService: TwilioService

        @Bean
        public fun deleteStudyUsageDataRunner() : DeleteStudyUsageDataRunner {
            return DeleteStudyUsageDataRunner(storageResolver)
        }

        @Bean
        public fun deleteStudyTUDSubmissionDataRunner() : DeleteStudyTUDSubmissionDataRunner {
                return DeleteStudyTUDSubmissionDataRunner()
        }

        @Bean
        public fun deleteAppUsageSurveyDataRunner() : DeleteStudyAppUsageSurveyDataRunner {
                return DeleteStudyAppUsageSurveyDataRunner()
        }

        @Bean
        public fun deleteStudyTableDataRunner() : DeleteStudyTableDataRunner {
                return DeleteStudyTableDataRunner(storageResolver)
        }

        @Bean
        public fun deleteParticipantUsageDataRunner() : DeleteParticipantUsageDataRunner {
                return DeleteParticipantUsageDataRunner(storageResolver)
        }

        @Bean
        public fun deleteParticipantTUDSubmissionDataRunner() : DeleteParticipantTUDSubmissionDataRunner {
                return DeleteParticipantTUDSubmissionDataRunner()
        }

        @Bean
        public fun deleteParticipantAppUsageSurveyDataRunner() : DeleteParticipantAppUsageSurveyDataRunner {
                return DeleteParticipantAppUsageSurveyDataRunner()
        }

        @Bean
        public fun deleteParticipantStatsDataRunner() : DeleteParticipantStatsDataRunner {
                return DeleteParticipantStatsDataRunner()
        }

        @Bean
        public fun deleteParticipantUploadBufferDataRunner() : DeleteParticipantUploadBufferDataRunner {
                return DeleteParticipantUploadBufferDataRunner()
        }

        @Bean
        public fun deleteParticipantSensorDataRunner() : DeleteParticipantSensorDataRunner {
                return DeleteParticipantSensorDataRunner(storageResolver)
        }

        @Bean
        public fun deleteParticipantAndroidSensorDataRunner() : DeleteParticipantAndroidSensorDataRunner {
                return DeleteParticipantAndroidSensorDataRunner()
        }

        @Bean
        public fun deleteParticipantBatteryTelemetryDataRunner() : DeleteParticipantBatteryTelemetryDataRunner {
                return DeleteParticipantBatteryTelemetryDataRunner()
        }

        @Bean
        public fun deleteParticipantInteractionEventsDataRunner() : DeleteParticipantInteractionEventsDataRunner {
                return DeleteParticipantInteractionEventsDataRunner()
        }

        @Bean
        public fun deleteParticipantAppAudioActivityDataRunner() : DeleteParticipantAppAudioActivityDataRunner {
                return DeleteParticipantAppAudioActivityDataRunner()
        }

        @Bean
        public fun deleteParticipantAppAudioContentDataRunner() : DeleteParticipantAppAudioContentDataRunner {
                return DeleteParticipantAppAudioContentDataRunner()
        }

        @Bean
        public fun deleteParticipantAmbientAudioDataRunner() : DeleteParticipantAmbientAudioDataRunner {
                return DeleteParticipantAmbientAudioDataRunner()
        }

        @Bean
        public fun deleteParticipantNotificationActivityDataRunner() : DeleteParticipantNotificationActivityDataRunner {
                return DeleteParticipantNotificationActivityDataRunner()
        }

        @Bean
        public fun deleteParticipantSleepEventsDataRunner() : DeleteParticipantSleepEventsDataRunner {
                return DeleteParticipantSleepEventsDataRunner()
        }

        @Bean
        public fun deleteParticipantActivityRecognitionEventsDataRunner() : DeleteParticipantActivityRecognitionEventsDataRunner {
                return DeleteParticipantActivityRecognitionEventsDataRunner()
        }

        @Bean
        public fun deleteParticipantHealthMetricsDataRunner() : DeleteParticipantHealthMetricsDataRunner {
                return DeleteParticipantHealthMetricsDataRunner()
        }

        @Bean
        public fun deleteParticipantConnectivityStateEventsDataRunner() : DeleteParticipantConnectivityStateEventsDataRunner {
                return DeleteParticipantConnectivityStateEventsDataRunner()
        }

        @Bean
        public fun deleteParticipantAppNetworkUsageDataRunner() : DeleteParticipantAppNetworkUsageDataRunner {
                return DeleteParticipantAppNetworkUsageDataRunner()
        }

        @Bean
        public fun deleteParticipantDeviceSettingsDataRunner() : DeleteParticipantDeviceSettingsDataRunner {
                return DeleteParticipantDeviceSettingsDataRunner()
        }

        @Bean
        public fun deleteParticipantPreprocessedUsageDataRunner() : DeleteParticipantPreprocessedUsageDataRunner {
                return DeleteParticipantPreprocessedUsageDataRunner(storageResolver)
        }

        @Bean
        public fun deleteParticipantQuestionnaireSubmissionDataRunner() : DeleteParticipantQuestionnaireSubmissionDataRunner {
                return DeleteParticipantQuestionnaireSubmissionDataRunner()
        }

        @Bean
        public fun deleteParticipantUsageStatsDataRunner() : DeleteParticipantUsageStatsDataRunner {
                return DeleteParticipantUsageStatsDataRunner(storageResolver)
        }

        @Bean
        public fun deleteParticipantRegisteredAssetDataRunner(): DeleteParticipantRegisteredAssetDataRunner {
                return DeleteParticipantRegisteredAssetDataRunner()
        }

        @Bean
        public fun notificationJobRunner() : NotificationJobRunner {
                return NotificationJobRunner(twilioService)
        }

        @Bean
        public fun pipelineJobRunner() : com.openlattice.chronicle.pipeline.PipelineJobRunner {
                return com.openlattice.chronicle.pipeline.PipelineJobRunner(storageResolver)
        }

}
