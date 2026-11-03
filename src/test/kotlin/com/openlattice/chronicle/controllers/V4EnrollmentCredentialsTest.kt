package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.filters.MobileApiHmacAuthenticationToken
import com.openlattice.chronicle.filters.MobileEnrollmentAuthenticationToken
import org.junit.Assert.assertThrows
import org.junit.Test
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

class V4EnrollmentCredentialsTest {
    private val studyId = UUID.randomUUID()

    @Test
    fun `one time bootstrap requires the complete replay safe credential set`() {
        val attemptId = UUID.randomUUID().toString()
        val proposedApiKey = "ck_01234567_0123456789ABCDEFGHIJKLMNOPQRSTUV" // gitleaks:allow -- deterministic test credential
        requireV4EnrollmentCredentials(
                MobileEnrollmentAuthenticationToken(studyId),
                "a".repeat(64),
                "b".repeat(64),
                attemptId,
                proposedApiKey,
        )
        listOf(
            listOf(null, "b".repeat(64), attemptId, proposedApiKey),
            listOf("a".repeat(64), null, attemptId, proposedApiKey),
            listOf("a".repeat(64), "b".repeat(64), null, proposedApiKey),
            listOf("a".repeat(64), "b".repeat(64), attemptId, null),
        ).forEach { headers ->
            assertThrows(ResponseStatusException::class.java) {
                requireV4EnrollmentCredentials(
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
    fun `deployment signatures cannot enroll without a one time invitation`() {
        assertThrows(ResponseStatusException::class.java) {
            requireV4EnrollmentCredentials(
                MobileApiHmacAuthenticationToken(studyId),
                null,
                null,
                null,
                null,
            )
        }
        assertThrows(ResponseStatusException::class.java) {
            requireV4EnrollmentCredentials(
                MobileApiHmacAuthenticationToken(studyId),
                "a".repeat(64),
                "b".repeat(64),
                UUID.randomUUID().toString(),
                "ck_01234567_0123456789ABCDEFGHIJKLMNOPQRSTUV",
            )
        }
    }
}
