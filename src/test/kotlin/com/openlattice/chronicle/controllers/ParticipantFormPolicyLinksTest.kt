package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.participantaccess.ParticipantFormSessionResponse
import com.openlattice.chronicle.study.StudyParticipantPolicy
import com.openlattice.chronicle.study.StudySettingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime
import java.util.UUID

class ParticipantFormPolicyLinksTest {
    private val session = ParticipantFormSessionResponse(
        csrfToken = "csrf",
        studyId = UUID.randomUUID(),
        participantId = "p1",
        formKind = ParticipantFormKind.APP_USAGE,
        expiresAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun `session carries the study privacy and withdrawal links`() {
        val policy = StudyParticipantPolicy(
            responsibleInstitution = "Inst",
            serverOperator = "Inst",
            researchContact = "r@example.org",
            purpose = "p",
            expectedDuration = "d",
            procedures = "p",
            foreseeableRisks = "r",
            expectedBenefits = "b",
            dataUseAndSharing = "u",
            retentionAndDeletion = "r",
            privacyPolicyUrl = "https://example.org/privacy",
            withdrawalUrl = "https://example.org/withdrawal",
            version = "v1",
            effectiveAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        )

        val linked = session.withPolicyLinks(mapOf(StudySettingType.ParticipantPolicy to policy))

        assertEquals("https://example.org/privacy", linked.privacyPolicyUrl)
        assertEquals("https://example.org/withdrawal", linked.withdrawalUrl)
    }

    @Test
    fun `session without a participant policy has no links`() {
        val linked = session.withPolicyLinks(emptyMap())

        assertNull(linked.privacyPolicyUrl)
        assertNull(linked.withdrawalUrl)
    }
}
