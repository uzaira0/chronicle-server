package com.openlattice.chronicle.e2e.dsl.di

import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.participants.Participant
import com.openlattice.chronicle.sources.AndroidDevice
import com.openlattice.chronicle.study.Study
import java.util.UUID

interface TestDataProvider {
    fun study(tag: String = "Test"): Study
    fun participant(): Participant
    fun androidDevice(): AndroidDevice
    fun usageEvents(studyId: UUID, participantId: String, count: Int = 25): ChronicleData
}
