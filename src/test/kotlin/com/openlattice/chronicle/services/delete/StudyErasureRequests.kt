package com.openlattice.chronicle.services.delete

import java.util.UUID

/** Records a study erasure request without the lifecycle change the dashboard path makes with it. */
internal fun DataDeletionOrchestrator.quarantineStudy(studyId: UUID, requestedBy: String, idempotencyKey: UUID): UUID =
    quarantineStudyAtomically(studyId, requestedBy, idempotencyKey) { _, _, _ -> }
