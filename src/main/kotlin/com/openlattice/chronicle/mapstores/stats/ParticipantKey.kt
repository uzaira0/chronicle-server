package com.openlattice.chronicle.mapstores.stats

import java.util.*

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@getmethodic.com&gt;
 */
public data class ParticipantKey(
    val studyId: UUID,
    val participantId: String
) {
    override fun toString(): String =
        "ParticipantKey(studyId=$studyId, participantRef=${com.openlattice.chronicle.audit.AuditService.participantReference(participantId)})"
}
