package com.openlattice.chronicle.property

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.guava.GuavaModule
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.openlattice.chronicle.candidates.Candidate
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.participants.Participant
import com.openlattice.chronicle.study.Study
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.*

/**
 * Property-based serialization tests using Kotest's Arb generators.
 *
 * Verifies that arbitrary valid Study and Participant objects survive
 * Jackson JSON round-trip (serialize -> deserialize) with key fields intact.
 */
class SerializationProperties {

    private val mapper: ObjectMapper = ObjectMapper().apply {
        registerModule(Jdk8Module())
        registerModule(JavaTimeModule())
        registerModule(GuavaModule())
        registerModule(KotlinModule.Builder().build())
        configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
    }

    /**
     * Arb for alphabetic strings (matching Study.storage's alphabetic constraint).
     */
    private val alphabeticArb: Arb<String> = Arb.stringPattern("[a-zA-Z]{1,20}")

    /**
     * Arb for non-blank strings (title and contact must not be blank).
     */
    private val nonBlankArb: Arb<String> = Arb.stringPattern("[a-zA-Z0-9]{1,50}")

    /**
     * Arb for email-like contact strings.
     */
    private val contactArb: Arb<String> = Arb.stringPattern("[a-z]{3,10}@[a-z]{3,8}\\.com")

    @Test
    fun `Study round-trips title through JSON serialization`() { runBlocking {
        forAll(100, nonBlankArb, contactArb) { title, contact ->
            val study = Study(
                title = title,
                contact = contact
            )
            val json = mapper.writeValueAsString(study)
            val deserialized = mapper.readValue(json, Study::class.java)
            deserialized.title == study.title
        }
    } }

    @Test
    fun `Study round-trips contact through JSON serialization`() { runBlocking {
        forAll(100, nonBlankArb, contactArb) { title, contact ->
            val study = Study(
                title = title,
                contact = contact
            )
            val json = mapper.writeValueAsString(study)
            val deserialized = mapper.readValue(json, Study::class.java)
            deserialized.contact == study.contact
        }
    } }

    @Test
    fun `Study round-trips coordinates through JSON serialization`() { runBlocking {
        forAll(
            100,
            nonBlankArb,
            contactArb,
            Arb.double(-90.0..90.0),
            Arb.double(-180.0..180.0)
        ) { title, contact, lat, lon ->
            val study = Study(
                title = title,
                contact = contact,
                lat = lat,
                lon = lon
            )
            val json = mapper.writeValueAsString(study)
            val deserialized = mapper.readValue(json, Study::class.java)
            // Floating-point round-trip should be exact for JSON
            deserialized.lat == study.lat && deserialized.lon == study.lon
        }
    } }

    @Test
    fun `Study round-trips storage through JSON serialization`() { runBlocking {
        forAll(100, nonBlankArb, contactArb, alphabeticArb) { title, contact, storage ->
            val study = Study(
                title = title,
                contact = contact,
                storage = storage
            )
            val json = mapper.writeValueAsString(study)
            val deserialized = mapper.readValue(json, Study::class.java)
            deserialized.storage == study.storage
        }
    } }

    @Test
    fun `Participant round-trips through JSON serialization`() { runBlocking {
        forAll(100, Arb.stringPattern("[a-zA-Z0-9_.-]{1,50}")) { participantId ->
            val participant = Participant(
                participantId = participantId,
                candidate = Candidate(),
                participationStatus = ParticipationStatus.ENROLLED
            )
            val json = mapper.writeValueAsString(participant)
            val deserialized = mapper.readValue(json, Participant::class.java)
            deserialized.participantId == participant.participantId
                && deserialized.participationStatus == participant.participationStatus
        }
    } }

    @Test
    fun `Participant round-trips all ParticipationStatus values`() { runBlocking {
        for (status in ParticipationStatus.values()) {
            val participant = Participant(
                participantId = "prop-test-${status.name}",
                candidate = Candidate(),
                participationStatus = status
            )
            val json = mapper.writeValueAsString(participant)
            val deserialized = mapper.readValue(json, Participant::class.java)
            assertEquals(
                "ParticipationStatus $status did not survive round-trip",
                status, deserialized.participationStatus
            )
        }
    } }
}
