package com.openlattice.chronicle.parity

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.exc.ValueInstantiationException
import com.fasterxml.jackson.datatype.guava.GuavaModule
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.participants.Participant
import com.openlattice.chronicle.study.Study
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Cross-implementation parity tests.
 *
 * Reads shared fixture JSON files from tests/parity/fixtures/ (relative to the monorepo root)
 * and verifies that:
 * - Valid fixtures deserialize and round-trip correctly
 * - Invalid fixtures throw the expected exceptions
 *
 * The same fixture files are consumed by the TypeScript parity test in chronicle-web,
 * ensuring both implementations agree on validation semantics.
 */
class ParityTest {

    private val mapper: ObjectMapper = ObjectMapper().apply {
        registerModule(Jdk8Module())
        registerModule(JavaTimeModule())
        registerModule(GuavaModule())
        registerModule(KotlinModule.Builder().build())
        configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
    }

    /**
     * Resolve fixture directory relative to the chronicle-server submodule.
     * In the monorepo layout: chronicle-server/../tests/parity/fixtures/
     */
    private fun fixtureFile(name: String): File {
        // Try monorepo-relative path first (when running from chronicle-server/)
        val fromSubmodule = File("../tests/parity/fixtures/$name")
        if (fromSubmodule.exists()) return fromSubmodule

        // Fallback: running from monorepo root
        val fromRoot = File("tests/parity/fixtures/$name")
        if (fromRoot.exists()) return fromRoot

        error("Fixture file not found: $name (tried ${fromSubmodule.absolutePath} and ${fromRoot.absolutePath})")
    }

    // -------------------------------------------------------------------------
    // Study fixtures
    // -------------------------------------------------------------------------

    @Test
    fun `valid study fixture deserializes successfully`() {
        val json = fixtureFile("study.valid.json").readText()
        val study = mapper.readValue(json, Study::class.java)

        assertEquals("Sleep Patterns Study", study.title)
        assertEquals("researcher@university.edu", study.contact)
        assertEquals(37.4275, study.lat, 0.0001)
        assertEquals(-122.1697, study.lon, 0.0001)
        assertEquals("neuroscience", study.group)
        assertEquals("1.0", study.version)
    }

    @Test
    fun `valid study fixture round-trips through serialization`() {
        val json = fixtureFile("study.valid.json").readText()
        val study = mapper.readValue(json, Study::class.java)
        val reserialized = mapper.writeValueAsString(study)
        val roundTripped = mapper.readValue(reserialized, Study::class.java)

        assertEquals(study.title, roundTripped.title)
        assertEquals(study.contact, roundTripped.contact)
        assertEquals(study.lat, roundTripped.lat, 0.0001)
        assertEquals(study.lon, roundTripped.lon, 0.0001)
        assertEquals(study.group, roundTripped.group)
        assertEquals(study.version, roundTripped.version)
        assertEquals(study.notificationsEnabled, roundTripped.notificationsEnabled)
        assertEquals(study.storage, roundTripped.storage)
    }

    @Test
    fun `invalid UUID study fixture throws on deserialization`() {
        val json = fixtureFile("study.invalid-uuid.json").readText()
        try {
            mapper.readValue(json, Study::class.java)
            fail("Expected deserialization to fail for invalid UUID")
        } catch (e: InvalidFormatException) {
            // Expected: Jackson cannot parse "not-a-uuid" as UUID
            assertTrue(
                "Exception should mention UUID parsing",
                e.message?.contains("UUID") == true || e.message?.contains("not-a-uuid") == true
            )
        } catch (expected: ValueInstantiationException) {
            // Also acceptable if the error propagates through the constructor
        }
    }

    @Test
    fun `missing-required study fixture fails`() {
        val json = fixtureFile("study.missing-required.json").readText()
        try {
            mapper.readValue(json, Study::class.java)
            fail("Expected deserialization to fail for missing required 'title' field")
        } catch (expected: ValueInstantiationException) {
            // Expected: title is required by the @JsonCreator factory method
        } catch (e: Exception) {
            // Some Jackson/Kotlin module versions may throw different exception types
            // for missing required parameters — that's acceptable
            assertTrue(
                "Exception should relate to missing title",
                e.message?.contains("title") == true
                    || e.message?.contains("parameter") == true
                    || e.message?.contains("required") == true
                    || e.javaClass.simpleName.contains("Missing")
            )
        }
    }

    // -------------------------------------------------------------------------
    // Participant fixtures
    // -------------------------------------------------------------------------

    @Test
    fun `valid participant fixture deserializes successfully`() {
        val json = fixtureFile("participant.valid.json").readText()
        val participant = mapper.readValue(json, Participant::class.java)

        assertEquals("p-sleep-001", participant.participantId)
        assertEquals(ParticipationStatus.ENROLLED, participant.participationStatus)
        assertEquals("Completed initial screening", participant.participantNotes)
        assertTrue(participant.participantTags.contains("control"))
        assertTrue(participant.participantTags.contains("wave-1"))
    }

    @Test
    fun `valid participant fixture round-trips through serialization`() {
        val json = fixtureFile("participant.valid.json").readText()
        val participant = mapper.readValue(json, Participant::class.java)
        val reserialized = mapper.writeValueAsString(participant)
        val roundTripped = mapper.readValue(reserialized, Participant::class.java)

        assertEquals(participant.participantId, roundTripped.participantId)
        assertEquals(participant.participationStatus, roundTripped.participationStatus)
        assertEquals(participant.participantNotes, roundTripped.participantNotes)
        assertEquals(participant.participantTags, roundTripped.participantTags)
    }

    @Test
    fun `invalid participation status throws on deserialization`() {
        val json = fixtureFile("participant.invalid-status.json").readText()
        try {
            mapper.readValue(json, Participant::class.java)
            fail("Expected deserialization to fail for invalid participationStatus 'BOGUS'")
        } catch (e: InvalidFormatException) {
            // Expected: "BOGUS" is not a valid ParticipationStatus enum value
            assertTrue(
                "Exception should mention the invalid value",
                e.message?.contains("BOGUS") == true
            )
        } catch (expected: ValueInstantiationException) {
            // Also acceptable
        }
    }
}
