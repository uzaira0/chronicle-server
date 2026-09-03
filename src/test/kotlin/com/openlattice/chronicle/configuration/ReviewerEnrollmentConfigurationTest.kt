package com.openlattice.chronicle.configuration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class ReviewerEnrollmentConfigurationTest {
    @Test
    fun `disabled reviewer configuration exposes no scope even if stale values remain`() {
        assertNull(
            ReviewerEnrollmentConfiguration(
                enabled = false,
                secret = "stale-secret",
                studyId = "not-a-uuid",
                participantId = "stale-participant",
            ).validatedScopeOrNull(),
        )
    }

    @Test
    fun `enabled reviewer configuration requires a strong secret and complete valid scope`() {
        val studyId = UUID.fromString("00000000-0000-0000-0000-000000000401")
        val valid = ReviewerEnrollmentConfiguration(
            enabled = true,
            secret = "random-reviewer-secret-with-at-least-32-chars",
            studyId = studyId.toString(),
            participantId = "play-reviewer",
        )

        assertEquals(studyId, valid.validatedScopeOrNull()?.studyId)
        assertEquals("play-reviewer", valid.validatedScopeOrNull()?.participantId)

        assertThrows(IllegalStateException::class.java) {
            valid.copy(secret = "too-short").validatedScopeOrNull()
        }
        assertThrows(IllegalStateException::class.java) {
            valid.copy(secret = " ".repeat(32)).validatedScopeOrNull()
        }
        assertThrows(IllegalStateException::class.java) {
            valid.copy(secret = "a".repeat(257)).validatedScopeOrNull()
        }
        assertThrows(IllegalStateException::class.java) {
            valid.copy(studyId = "").validatedScopeOrNull()
        }
        assertThrows(IllegalStateException::class.java) {
            valid.copy(participantId = "not a safe id").validatedScopeOrNull()
        }
    }
}
