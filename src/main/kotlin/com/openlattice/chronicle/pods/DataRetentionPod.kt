package com.openlattice.chronicle.pods

import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.mapstores.stats.ParticipantStatsCache
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.services.delete.DataDeletionOrchestrator
import com.openlattice.chronicle.services.delete.ParticipantPurgeService
import com.openlattice.chronicle.services.download.DataDownloadManager
import com.openlattice.chronicle.services.download.DataDownloadService
import com.openlattice.chronicle.services.download.StoredDataRepository
import com.openlattice.chronicle.services.download.StoredExportTypes
import com.openlattice.chronicle.storage.StorageResolver
import java.io.IOException
import java.util.concurrent.ExecutionException
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

@Configuration
@Import(StoredDataRepository::class, StoredExportTypes::class)
public open class DataRetentionPod(
    private val storageResolver: StorageResolver,
) {

    @Bean
    public fun dataDeletionOrchestrator(
        participantStatsCache: ParticipantStatsCache,
        auditingManager: AuditingManager,
    ): DataDeletionOrchestrator =
        DataDeletionOrchestrator(
            storageResolver,
            auditingManager,
            participantStatsCache,
        )

    @Bean
    public fun participantPurgeService(
        dataDeletionOrchestrator: DataDeletionOrchestrator,
        apiKeyService: ApiKeyService,
        auditingManager: AuditingManager,
    ): ParticipantPurgeService {
        return ParticipantPurgeService(
            storageResolver,
            dataDeletionOrchestrator,
            apiKeyService,
            auditingManager,
        )
    }

    @Bean
    @Throws(IOException::class, ExecutionException::class)
    public fun dataDownloadManager(): DataDownloadManager {
        return DataDownloadService(storageResolver)
    }
}
