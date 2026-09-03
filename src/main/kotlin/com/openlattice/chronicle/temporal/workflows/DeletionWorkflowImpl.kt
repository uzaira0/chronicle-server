package com.openlattice.chronicle.temporal.workflows

import com.openlattice.chronicle.temporal.activities.DeletionActivities
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration

public class DeletionWorkflowImpl : DeletionWorkflow {

    private val activities: DeletionActivities = Workflow.newActivityStub(
        DeletionActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(30))
            .setHeartbeatTimeout(Duration.ofMinutes(5))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(10))
                    .setMaximumInterval(Duration.ofMinutes(10))
                    .setBackoffCoefficient(2.0)
                    .setMaximumAttempts(3)
                    .build()
            )
            .build()
    )

    override fun deleteData(request: DeletionRequest) {
        var totalDeleted = 0L

        for (deletionType in request.deletionTypes) {
            val deleted = activities.deleteData(
                jobId = request.jobId,
                deletionType = deletionType,
                studyId = request.studyId,
                participantIds = request.participantIds,
            )
            totalDeleted += deleted

            activities.auditDeletion(
                studyId = request.studyId,
                deletionType = deletionType,
                participantIds = request.participantIds,
                deletedRows = deleted,
                principalId = request.principalId,
                securablePrincipalId = request.securablePrincipalId,
            )
        }
    }
}
