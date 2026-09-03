package com.openlattice.chronicle.upgrades

import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewerEnrollmentIssuanceMigrationTest {
    @Test
    fun `migration deduplicates and uniquely constrains live reviewer invitations`() {
        val sql = checkNotNull(
            javaClass.getResource("/db/migration/V92__serialize_reviewer_enrollment_issuance.sql"),
        ).readText()

        assertTrue(sql.contains("row_number() OVER"))
        assertTrue(sql.contains("issued_by = 'play-reviewer-bootstrap'"))
        assertTrue(sql.contains("CREATE UNIQUE INDEX"))
        assertTrue(sql.contains("exchanged_at IS NULL"))
        assertTrue(sql.contains("revoked_at IS NULL"))
    }
}
