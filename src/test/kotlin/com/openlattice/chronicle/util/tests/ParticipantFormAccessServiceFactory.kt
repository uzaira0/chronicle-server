package com.openlattice.chronicle.util.tests

import com.openlattice.chronicle.auditing.AuditingManager
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.configuration.MobileApiSignatureFilterTest
import org.mockito.kotlin.mock
import java.time.Clock

internal fun participantFormAccessServiceForTest(
    storageResolver: StorageResolver,
    clock: Clock = Clock.systemUTC(),
    auditingManager: AuditingManager = mock(),
): ParticipantFormAccessService = ParticipantFormAccessService(storageResolver, auditingManager,
    MobileApiSignatureFilterTest.SECRET.toByteArray(Charsets.UTF_8), clock)
