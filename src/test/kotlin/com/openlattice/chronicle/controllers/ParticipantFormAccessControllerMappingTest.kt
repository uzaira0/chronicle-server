package com.openlattice.chronicle.controllers

import org.junit.Assert.assertEquals
import org.junit.Test
import org.springframework.web.bind.annotation.PostMapping

class ParticipantFormAccessControllerMappingTest {

    @Test
    fun `form access endpoint supports servlet-stripped and direct paths`() {
        val endpoint = ParticipantFormAccessController::class.java.declaredMethods
            .single { method -> method.name == "createAccessCode" }
        val mapping = requireNotNull(endpoint.getAnnotation(PostMapping::class.java))

        assertEquals(
            setOf(
                "/v3/study/{studyId}/participant/{participantId}/form-access-codes",
                "/chronicle/v3/study/{studyId}/participant/{participantId}/form-access-codes",
                "/v4/study/{studyId}/participant/{participantId}/form-access-codes",
                "/chronicle/v4/study/{studyId}/participant/{participantId}/form-access-codes",
            ),
            mapping.path.toSet(),
        )
    }

    @Test
    fun `form access exchange supports servlet-stripped and direct paths`() {
        val endpoint = ParticipantFormAccessController::class.java.declaredMethods
            .single { method -> method.name == "exchangeAccessCode" }
        val mapping = requireNotNull(endpoint.getAnnotation(PostMapping::class.java))

        assertEquals(
            setOf(
                "/chronicle/v3/participant-access/exchange",
                "/v3/participant-access/exchange",
            ),
            mapping.path.toSet(),
        )
    }
}
