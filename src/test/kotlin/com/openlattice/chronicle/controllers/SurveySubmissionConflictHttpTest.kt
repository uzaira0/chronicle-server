package com.openlattice.chronicle.controllers

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.services.participantaccess.ParticipantSubmissionConflictException
import com.openlattice.chronicle.services.surveys.AppUsageDayConflictException
import com.openlattice.chronicle.survey.AppUsageDayConflict
import org.junit.Assert.assertEquals
import org.junit.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/** A refused survey submission is a 409 the page can explain, never the retryable 500 "temporarily busy". */
class SurveySubmissionConflictHttpTest : ChronicleServerExceptionHandlerTestSupport() {
    @RestController
    class Throwing {
        @PostMapping("/receipt") fun receipt(): Unit = throw ParticipantSubmissionConflictException("different payload")

        @PostMapping("/day/{conflict}") fun day(@PathVariable conflict: AppUsageDayConflict): Unit =
            throw AppUsageDayConflictException(conflict)
    }

    private val mvc = MockMvcBuilders.standaloneSetup(Throwing()).setControllerAdvice(handler).build()

    @Test fun receiptConflictIsAConflict() {
        assertEquals(409, mvc.perform(post("/receipt")).andReturn().response.status)
    }

    @Test fun dayConflictCarriesItsReason() {
        AppUsageDayConflict.entries.forEach { conflict ->
            val response = mvc.perform(post("/day/${conflict.name}")).andReturn().response
            assertEquals(409, response.status)
            assertEquals(conflict.name, ObjectMappers.getJsonMapper().readTree(response.contentAsString).path("code").asText())
        }
    }
}
