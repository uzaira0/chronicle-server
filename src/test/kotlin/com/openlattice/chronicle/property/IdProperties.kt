package com.openlattice.chronicle.property

import io.kotest.property.Arb
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.stringPattern
import io.kotest.property.arbitrary.uuid
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.*

public class IdProperties {

    @Test
    public fun `UUID toString and fromString are inverse operations`(): Unit = runBlocking {
        forAll(200, Arb.uuid()) { uuid ->
            UUID.fromString(uuid.toString()) == uuid
        }
    }

    @Test
    public fun `generated UUIDs are always version 4`(): Unit = runBlocking {
        forAll(200, Arb.constant(Unit)) { _ ->
            val uuid = UUID.randomUUID()
            uuid.version() == 4
        }
    }

    @Test
    public fun `UUIDs are unique across generations`(): Unit = runBlocking {
        val uuids = (1..1000).map { UUID.randomUUID() }.toSet()
        assertEquals("All 1000 generated UUIDs should be unique", 1000, uuids.size)
    }

    @Test
    public fun `malformed UUID strings never parse successfully`(): Unit = runBlocking {
        val malformed = Arb.stringPattern("[a-z0-9]{1,40}")
        forAll(100, malformed) { str ->
            if (str.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))) {
                true
            } else {
                try {
                    UUID.fromString(str)
                    false
                } catch (expected: IllegalArgumentException) {
                    true
                }
            }
        }
    }
}
