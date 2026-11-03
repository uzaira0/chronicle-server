package com.openlattice.chronicle.exceptions

import com.openlattice.chronicle.i18n.Messages
import java.util.UUID

public open class StudyRegistrationNotFoundException : RuntimeException {
    internal constructor(message: String) : super(message)
    internal constructor(message: String, cause: Throwable) : super(message, cause)
}

public open class CandidateNotFoundException(candidateId: UUID, message: String? = "$candidateId") : RuntimeException(message)
public open class StudyNotFoundException(
    public val studyId: UUID,
    message: String = Messages.get("error.study.notFound"),
    cause: Throwable? = null,
) :
    RuntimeException(message, cause)
public open class OrganizationNotFoundException(public val organization: UUID, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
