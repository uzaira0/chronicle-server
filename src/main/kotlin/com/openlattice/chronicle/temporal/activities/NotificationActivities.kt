package com.openlattice.chronicle.temporal.activities

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import java.util.UUID

/**
 * Activities for sending notifications via Twilio.
 * Each activity method is a single unit of work that Temporal can retry independently.
 */
@ActivityInterface
public interface NotificationActivities {

    /**
     * Sends an SMS notification and returns the Twilio message SID.
     */
    @ActivityMethod
    public fun sendSms(studyId: UUID, participantId: UUID, destination: String, body: String): String

    /**
     * Updates the notification record with the Twilio message ID.
     */
    @ActivityMethod
    public fun recordMessageId(notificationId: UUID, messageId: String)

    /**
     * Records an audit event for the notification.
     */
    // reason: Temporal @ActivityMethod contract — the audit fields are the activity's serialized
    // inputs and the signature cannot be changed without breaking the workflow/activity wire contract
    @Suppress("LongParameterList")
    @ActivityMethod
    public fun auditNotification(
        studyId: UUID,
        participantId: UUID,
        destination: String,
        deliveryType: String,
        notificationType: String,
        principalId: String,
        securablePrincipalId: UUID,
        dropped: Boolean,
    )
}
