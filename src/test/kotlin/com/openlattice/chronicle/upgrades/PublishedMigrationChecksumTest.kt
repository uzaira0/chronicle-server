package com.openlattice.chronicle.upgrades

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class PublishedMigrationChecksumTest {
    @Test
    fun `published V27 remains byte for byte immutable`() {
        val migration = checkNotNull(
            javaClass.getResourceAsStream("/db/migration/V27__collection_decision_trail.sql"),
        ).use { it.readBytes() }

        assertEquals(
            "7ce0c0f1bb3cb4bffdca20cbeee1aa5a7096cafc394164b9748cd648d61dbf31",
            migration.sha256(),
        )
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
