package com.openlattice.chronicle.temporal.activities

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import java.util.UUID

/**
 * Activities for deleting participant and study data.
 * Each deletion type is a separate activity so Temporal can retry individually.
 */
@ActivityInterface
public interface DeletionActivities {

    /**
     * Deletes data of the specified type for a study or participant.
     * Returns the number of rows deleted.
     */
    @ActivityMethod
    public fun deleteData(
        jobId: UUID,
        deletionType: String,
        studyId: UUID,
        participantIds: List<UUID>,
    ): Long

    /**
     * Records an audit event for the deletion.
     */
    @ActivityMethod
    public fun auditDeletion(
        studyId: UUID,
        deletionType: String,
        participantIds: List<UUID>,
        deletedRows: Long,
        principalId: String,
        securablePrincipalId: UUID,
    )
}
