package com.openlattice.chronicle.upgrades

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Self-host Postgres sets server-wide statement/lock timeouts; migrations must opt out of them. */
class FlywayMigrationSessionTimeoutTest {
    @Test
    fun migrationsRunWithoutServerTimeouts() {
        val initSql = FlywayMigrationService.baseConfiguration().initSql
        assertEquals(FlywayMigrationService.MIGRATION_SESSION_SQL, initSql)
        for (setting in listOf("statement_timeout = 0", "lock_timeout = 0", "idle_in_transaction_session_timeout = 0")) {
            assertTrue("migration session must set $setting", initSql.contains(setting))
        }
    }
}
