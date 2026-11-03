package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionHaltGateMigrationTest {
    @Test
    fun currentSchemaHasItsAuthorityIndex() {
        ChronicleContractTestSchema.sharedPostgres.createConnection("").use { connection ->
            connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use {
                it.setString(1, "public.participant_collection_ack_one_decision_per_seq")
                it.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertTrue(rows.getBoolean(1))
                }
            }
        }
    }
}
