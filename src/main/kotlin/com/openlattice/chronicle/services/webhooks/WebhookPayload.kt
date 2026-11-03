package com.openlattice.chronicle.services.webhooks

import com.fasterxml.jackson.annotation.JsonValue
import com.openlattice.chronicle.util.LogSanitizer
import com.openlattice.chronicle.study.ParticipantDataType

/** External payloads carry a keyed reference; the raw participant label stays inside Chronicle. */
public class ParticipantRef private constructor(internal val participantId: String) {
    @get:JsonValue public val value: String = LogSanitizer.stableFingerprint(participantId, "participant")
    override fun toString(): String = value
    public companion object {
        public fun fromParticipantId(participantId: String): ParticipantRef =
            ParticipantRef(participantId)
    }
}

public sealed interface WebhookPayload {
    public data class ParticipantEnrolled(val participantRef: ParticipantRef, val participationStatus: String) : WebhookPayload
    public data class DataSubmitted(val participantRef: ParticipantRef, val dataType: ParticipantDataType, val recordCount: Int) : WebhookPayload
    public data class StudyStatusChanged(val previousStatus: String?, val newStatus: String, val reason: String) : WebhookPayload
    public data class Test(val test: Boolean = true) : WebhookPayload
}
