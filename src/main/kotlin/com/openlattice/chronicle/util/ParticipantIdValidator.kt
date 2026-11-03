package com.openlattice.chronicle.util

import com.openlattice.chronicle.participants.ParticipantIds

public fun validateParticipantId(id: String) {
    require(ParticipantIds.isValid(id)) { "Invalid participant ID format" }
}
