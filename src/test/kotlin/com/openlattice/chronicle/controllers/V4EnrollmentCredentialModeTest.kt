package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.filters.MobileApiHmacAuthenticationToken
import com.openlattice.chronicle.filters.MobileEnrollmentAuthenticationToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

class V4EnrollmentCredentialModeTest {
    private val studyId = UUID.randomUUID()

    @Test
    fun `one time bootstrap requires the complete replay safe credential set`() {
        val attemptId = UUID.randomUUID().toString()
        val proposedApiKey = "ck_01234567_0123456789ABCDEFGHIJKLMNOPQRSTUV" // gitleaks:allow -- deterministic test credential
        assertEquals(
            V4EnrollmentCredentialMode.ONE_TIME_CODE,
            requireV4EnrollmentCredentialMode(
                MobileEnrollmentAuthenticationToken(studyId),
                "a".repeat(64),
                "b".repeat(64),
                attemptId,
                proposedApiKey,
            ),
        )
        listOf(
            listOf(null, "b".repeat(64), attemptId, proposedApiKey),
            listOf("a".repeat(64), null, attemptId, proposedApiKey),
            listOf("a".repeat(64), "b".repeat(64), null, proposedApiKey),
            listOf("a".repeat(64), "b".repeat(64), attemptId, null),
        ).forEach { headers ->
            assertThrows(ResponseStatusException::class.java) {
                requireV4EnrollmentCredentialMode(
                    MobileEnrollmentAuthenticationToken(studyId),
                    headers[0],
                    headers[1],
                    headers[2],
                    headers[3],
                )
            }
        }
    }

    @Test
    fun `legacy signed bootstrap accepts only an absent code digest pair`() {
        assertEquals(
            V4EnrollmentCredentialMode.LEGACY_SIGNED_REQUEST,
            requireV4EnrollmentCredentialMode(
                MobileApiHmacAuthenticationToken(studyId),
                null,
                null,
                null,
                null,
            ),
        )
        assertThrows(ResponseStatusException::class.java) {
            requireV4EnrollmentCredentialMode(
                MobileApiHmacAuthenticationToken(studyId),
                "a".repeat(64),
                "b".repeat(64),
                UUID.randomUUID().toString(),
                "ck_01234567_0123456789ABCDEFGHIJKLMNOPQRSTUV",
            )
        }
    }
}
