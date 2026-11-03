package com.openlattice.chronicle.upgrades

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewerEnrollmentIssuanceMigrationTest {
    @Test
    fun currentSchemaHasItsAuthorityIndex() {
        ChronicleContractTestSchema.sharedPostgres.createConnection("").use { connection ->
            connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use {
                it.setString(1, "public.participant_form_access_codes_one_live_reviewer_enrollment")
                it.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertTrue(rows.getBoolean(1))
                }
            }
        }
    }
}
