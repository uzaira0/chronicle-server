package com.openlattice.chronicle.temporal.workflows

import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.util.UUID

/**
 * Temporal workflow for sending notifications with guaranteed delivery.
 *
 * Replaces the fire-and-forget pattern in NotificationJobRunner with durable execution:
 * - Automatic retry on Twilio failures (with exponential backoff)
 * - Visibility into notification state via Temporal UI
 * - Audit trail of all attempts
 */
@WorkflowInterface
public interface NotificationWorkflow {

    @WorkflowMethod
    public fun sendNotification(request: NotificationRequest)
}

public data class NotificationRequest(
    val notificationId: UUID,
    val studyId: UUID,
    val participantId: UUID,
    val destination: String,
    val body: String,
    val subject: String,
    val deliveryType: String,
    val notificationType: String,
    val principalId: String,
    val securablePrincipalId: UUID,
)
