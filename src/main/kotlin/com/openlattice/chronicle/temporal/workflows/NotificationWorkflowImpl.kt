package com.openlattice.chronicle.temporal.workflows

import com.openlattice.chronicle.temporal.activities.NotificationActivities
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration

public class NotificationWorkflowImpl : NotificationWorkflow {

    private val activities: NotificationActivities = Workflow.newActivityStub(
        NotificationActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(5))
                    .setMaximumInterval(Duration.ofMinutes(5))
                    .setBackoffCoefficient(2.0)
                    .setMaximumAttempts(5)
                    .build()
            )
            .build()
    )

    override fun sendNotification(request: NotificationRequest) {
        when (request.deliveryType) {
            "SMS" -> {
                val messageId = activities.sendSms(
                    request.studyId,
                    request.participantId,
                    request.destination,
                    request.body,
                )
                if (messageId.isNotEmpty()) {
                    activities.recordMessageId(request.notificationId, messageId)
                }
                activities.auditNotification(
                    studyId = request.studyId,
                    participantId = request.participantId,
                    destination = request.destination,
                    deliveryType = request.deliveryType,
                    notificationType = request.notificationType,
                    principalId = request.principalId,
                    securablePrincipalId = request.securablePrincipalId,
                    dropped = false,
                )
            }
            "EMAIL" -> {
                // Email delivery not supported — audit as dropped
                activities.auditNotification(
                    studyId = request.studyId,
                    participantId = request.participantId,
                    destination = request.destination,
                    deliveryType = request.deliveryType,
                    notificationType = request.notificationType,
                    principalId = request.principalId,
                    securablePrincipalId = request.securablePrincipalId,
                    dropped = true,
                )
            }
        }
    }
}
