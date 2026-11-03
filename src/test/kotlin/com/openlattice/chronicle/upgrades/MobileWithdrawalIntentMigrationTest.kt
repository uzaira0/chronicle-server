package com.openlattice.chronicle.upgrades

import org.junit.Assert.assertTrue
import org.junit.Test

class MobileWithdrawalIntentMigrationTest {
    @Test
    fun currentSchemaContainsDurableAuthorityFields() {
        val columns = migrationColumnNames("mobile_withdrawal_requests")
        assertTrue(columns.containsAll(setOf("request_id", "api_key_id", "study_id", "participant_id", "random_device_id", "already_withdrawn")))
    }
}
