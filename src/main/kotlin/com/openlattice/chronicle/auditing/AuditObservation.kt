package com.openlattice.chronicle.auditing

/** Independently persisted observations describe reads, requested intent, or an already committed effect. */
public sealed class AuditObservation private constructor(events: List<AuditableEvent>) {
    public val events: List<AuditableEvent> = events.toList()

    public class Read(events: List<AuditableEvent>) : AuditObservation(events) {
        init { require(this.events.all { isReadOrDenial(it.eventType) }) { "Mutation success requires its transaction owner" } }
    }

    public class Intent(events: List<AuditableEvent>) : AuditObservation(events)
    public class CommittedEffect(events: List<AuditableEvent>) : AuditObservation(events)

    internal companion object {
        fun isDenial(type: AuditEventType): Boolean = type == AuditEventType.ACCESS_DENIED ||
            type == AuditEventType.AUTHORIZATION_CHECK_FAILED || type == AuditEventType.STUDY_NOT_FOUND

        private val readTypes = setOf(
            AuditEventType.DOWNLOAD_TIME_USE_DIARY_DATA,
            AuditEventType.DOWNLOAD_PARTICIPANTS_TIME_USE_DIARY_DATA,
            AuditEventType.DOWNLOAD_PARTICIPANTS_DATA,
            AuditEventType.GET_FILTERED_APPS,
            AuditEventType.GET_ORGANIZATION,
            AuditEventType.GET_STUDY,
            AuditEventType.GET_TIME_USE_DIARY_SUBMISSION,
            AuditEventType.GET_ALL_STUDIES,
            AuditEventType.GET_PIPELINE_RUNS,
            AuditEventType.PREVIEW_PARTICIPANT_PURGE,
        )
        fun isReadOrDenial(type: AuditEventType): Boolean = isDenial(type) || type in readTypes
    }
}
