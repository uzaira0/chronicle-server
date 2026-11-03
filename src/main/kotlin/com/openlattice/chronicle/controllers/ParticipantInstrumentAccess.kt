package com.openlattice.chronicle.controllers

import com.geekbeast.controllers.exceptions.ForbiddenException
import com.openlattice.chronicle.authorization.AclKey
import com.openlattice.chronicle.authorization.AuthorizingComponent
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import java.util.UUID

/** Researcher ACLs and participant capabilities are separate authorities for instrument reads. */
internal fun AuthorizingComponent.ensureParticipantInstrumentRead(
    studyId: UUID,
    kind: ParticipantFormKind,
    resourceId: UUID? = null,
) {
    val scope = ParticipantFormAccessFilter.currentScope()
    if (scope == null) ensureReadAccess(AclKey(studyId))
    else if (!scope.permits(kind, studyId, null, resourceId)) {
        throw ForbiddenException(Messages.get(ParticipantFormAccessFilter.INVALID_SESSION_MESSAGE_KEY))
    }
}
