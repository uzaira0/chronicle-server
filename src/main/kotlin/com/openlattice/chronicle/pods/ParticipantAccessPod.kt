package com.openlattice.chronicle.pods

import com.fasterxml.jackson.databind.ObjectMapper
import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.configuration.MobileSecurityConfiguration
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.services.participantaccess.ParticipantFormSubmissionReceiptService
import com.openlattice.chronicle.storage.StorageResolver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
public open class ParticipantAccessPod(
    private val storageResolver: StorageResolver,
) {

    @Bean
    public fun participantFormAccessService(
        auditingManager: AuditingManager,
        mobileSecurityConfiguration: MobileSecurityConfiguration,
    ): ParticipantFormAccessService =
        ParticipantFormAccessService(storageResolver, auditingManager,
            mobileSecurityConfiguration.internalWebSecret.toByteArray(Charsets.UTF_8))

    @Bean
    public fun participantFormSubmissionReceiptService(
        secureObjectMapper: ObjectMapper,
    ): ParticipantFormSubmissionReceiptService =
        ParticipantFormSubmissionReceiptService(storageResolver, secureObjectMapper)
}
