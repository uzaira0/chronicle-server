package com.openlattice.chronicle.pods

import com.openlattice.chronicle.services.studies.StudyService
import com.openlattice.chronicle.services.upload.ActivityRecognitionEventsUploadService
import com.openlattice.chronicle.services.upload.AndroidSensorDataUploadService
import com.openlattice.chronicle.services.upload.AppAudioActivityUploadService
import com.openlattice.chronicle.services.upload.AppAudioContentUploadService
import com.openlattice.chronicle.services.upload.AppDataUploadManager
import com.openlattice.chronicle.services.upload.AppDataUploadService
import com.openlattice.chronicle.services.upload.AppNetworkUsageUploadService
import com.openlattice.chronicle.services.upload.BatteryTelemetryUploadService
import com.openlattice.chronicle.services.upload.ConnectivityStateEventsUploadService
import com.openlattice.chronicle.services.upload.DeviceSettingsUploadService
import com.openlattice.chronicle.services.upload.HealthMetricsUploadService
import com.openlattice.chronicle.services.upload.InteractionEventsUploadService
import com.openlattice.chronicle.services.upload.NotificationActivityUploadService
import com.openlattice.chronicle.services.upload.ScreenTimeUsageUploadService
import com.openlattice.chronicle.services.upload.SleepEventsUploadService
import com.openlattice.chronicle.services.upload.UploadDiagnosticsUploadService
import com.openlattice.chronicle.storage.StorageResolver
import java.io.IOException
import java.util.concurrent.ExecutionException
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
public open class DataIngestionPod(
    private val storageResolver: StorageResolver,
) {

    @Bean
    @Throws(IOException::class, ExecutionException::class)
    public fun appDataUploadManager(
        studyService: StudyService,
    ): AppDataUploadManager {
        return AppDataUploadService(
            storageResolver,
            studyService
        )
    }

    @Bean
    public fun screenTimeUsageUploadService(): ScreenTimeUsageUploadService {
        return ScreenTimeUsageUploadService(storageResolver)
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
}
