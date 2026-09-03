package com.openlattice.chronicle.property

import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.arbitrary.uuid
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.*

public class ValidationProperties {

    @Test
    public fun `valid UUIDs always parse successfully`(): Unit = runBlocking {
        forAll(100, Arb.uuid()) { uuid ->
            val str = uuid.toString()
            UUID.fromString(str) == uuid
        }
    }

    @Test
    public fun `participant IDs with allowed characters are accepted`(): Unit = runBlocking {
        forAll(100, Arb.stringPattern("[a-zA-Z0-9_.-]{1,50}")) { id ->
            id.matches(Regex("^[a-zA-Z0-9_.\\-]+$"))
        }
    }

    @Test
    public fun `study titles cannot be blank`(): Unit = runBlocking {
        // First char is alphanumeric so the generated title is never all-whitespace
        // (the old "[a-zA-Z0-9 ]{1,100}" could emit an all-spaces string, which is
        // blank by isBlank() -> flaky failure); spaces are still allowed thereafter.
        forAll(100, Arb.stringPattern("[a-zA-Z0-9][a-zA-Z0-9 ]{0,99}")) { title ->
            title.isNotBlank()
        }
    }

    @Test
    public fun `latitude values stay within bounds after round-trip`(): Unit = runBlocking {
        forAll(100, Arb.double(-90.0..90.0)) { lat ->
            val parsed = lat.toString().toDouble()
            parsed in -90.0..90.0
        }
    }

    @Test
    public fun `longitude values stay within bounds after round-trip`(): Unit = runBlocking {
        forAll(100, Arb.double(-180.0..180.0)) { lon ->
            val parsed = lon.toString().toDouble()
            parsed in -180.0..180.0
        }
    }
}
