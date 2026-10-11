package com.openlattice.chronicle.property

import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.util.validateParticipantId
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.forAll
import jakarta.validation.Validation
import kotlinx.coroutines.runBlocking
import org.junit.Test

public class ValidationProperties {
    @Test
    public fun `participant format validator accepts allowed IDs and rejects invalid characters and lengths`(): Unit = runBlocking {
        forAll(100, Arb.stringPattern("[a-zA-Z0-9_.-]{1,50}")) { id ->
            runCatching { validateParticipantId(id) }.isSuccess &&
                listOf("", "$id/", id.padEnd(256, 'a')).all { invalid ->
                    runCatching { validateParticipantId(invalid) }.exceptionOrNull() is IllegalArgumentException
                }
        }
    }

    @Test
    public fun `study model accepts nonblank titles and rejects blank titles`(): Unit = runBlocking {
        forAll(100, Arb.stringPattern("[a-zA-Z0-9][a-zA-Z0-9 ]{0,99}")) { title ->
            runCatching { Study(title = title, contact = "test@example.org") }.isSuccess &&
                listOf("", " ", "\t\n").all { blank ->
                    runCatching { Study(title = blank, contact = "test@example.org") }.exceptionOrNull() is IllegalArgumentException
                }
        }
    }

    @Test
    public fun `study model validates valid and invalid coordinates`(): Unit = runBlocking {
        Validation.buildDefaultValidatorFactory().use { factory ->
            val validator = factory.validator
            forAll(100, Arb.double(-90.0..90.0), Arb.double(-180.0..180.0)) { lat, lon ->
                validator.validate(Study(title = "Coordinates", contact = "test@example.org", lat = lat, lon = lon)).isEmpty() &&
                    listOf(-90.1 to lon, 90.1 to lon, lat to -180.1, lat to 180.1).all { (badLat, badLon) ->
                        validator.validate(Study(title = "Coordinates", contact = "test@example.org", lat = badLat, lon = badLon))
                            .any { it.propertyPath.toString() in setOf("lat", "lon") }
                    }
            }
        }
    }
}
