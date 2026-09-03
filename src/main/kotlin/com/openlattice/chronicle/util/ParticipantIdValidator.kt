package com.openlattice.chronicle.util

private val PARTICIPANT_ID_PATTERN = Regex("^[a-zA-Z0-9_.-]+$")

public fun validateParticipantId(id: String) {
    require(id.length in 1..255 && id.matches(PARTICIPANT_ID_PATTERN)) {
        "Invalid participant ID format"
    }
}
