package com.openlattice.chronicle.exceptions

/** Admission failed without exposing enrollment or study identifiers. */
internal class CollectionUnavailableException(val scope: Scope) : IllegalStateException("Collection is unavailable") {
    enum class Scope { STUDY, PARTICIPANT }
}
