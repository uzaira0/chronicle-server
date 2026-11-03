package com.openlattice.chronicle.audit

import com.openlattice.chronicle.auditing.AuditObservation
import com.openlattice.chronicle.auditing.AuditableEvent
import com.openlattice.chronicle.auditing.AuditingManager

/** A real [AuditService] whose one writer keeps what it was asked to record. */
internal class RecordingAuditingManager : AuditingManager {
    val observations = mutableListOf<AuditObservation>()
    val events: List<AuditableEvent> get() = observations.flatMap { it.events }

    override fun recordEvents(events: List<AuditableEvent>): Int = recordObservations(AuditObservation.Intent(events))

    /** Runs before each write; throw from it to model an unavailable audit store. */
    var beforeWrite: (AuditObservation) -> Unit = {}

    override fun recordObservations(observation: AuditObservation): Int {
        beforeWrite(observation)
        observations += observation
        return observation.events.size
    }
}

/** The outcome fields a request-level audit entry stores in its data. */
internal val AuditableEvent.success: Boolean get() = data["success"] as Boolean
internal val AuditableEvent.accessedPHI: Boolean get() = data["accessedPHI"] as Boolean
internal val AuditableEvent.responseCode: Int? get() = data["responseCode"] as? Int
internal val AuditableEvent.resourceId: String? get() = data["resourceId"] as? String

/** A real [AuditService] over a [RecordingAuditingManager]. */
internal fun recordingAuditService(): Pair<AuditService, RecordingAuditingManager> =
    RecordingAuditingManager().let { AuditService(it) to it }
