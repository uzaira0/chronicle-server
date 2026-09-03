package com.openlattice.chronicle.util

import com.openlattice.chronicle.util.SqlIdentifierValidator.InvalidSqlIdentifierException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mutation-killing tests for [SqlIdentifierValidator] that assert the SPECIFIC
 * rejection message / case-handling rather than merely that *an* exception is thrown.
 *
 * Why this is needed (kills mutants the existing "assertThrows(any)" tests miss):
 *  - RemoveConditional on each leading `isBlank()` guard: a blank input with the
 *    guard removed simply falls through to a LATER guard and still throws — same
 *    observable "throws", different message. Pinning the message distinguishes the
 *    branches and kills the mutant.
 *  - VoidMethodCall on `.uppercase()` / `.lowercase()`: killed by inputs whose
 *    acceptance FLIPS when the case-normalization is dropped (a lowercase keyword;
 *    an upper-case "PG_" system-catalog name).
 */
class SqlIdentifierValidatorKillTest {

    private fun message(block: () -> Unit): String =
        assertThrows(InvalidSqlIdentifierException::class.java) { block() }.message ?: ""

    // ---- blank guards: message must be the blank-specific one (kills RemoveConditional on isBlank) ----

    @Test
    fun `validateTableName blank rejected with blank-specific message`() {
        assertTrue(message { SqlIdentifierValidator.validateTableName("") }.contains("cannot be blank"))
    }

    @Test
    fun `validateTempTableName blank rejected with blank-specific message`() {
        assertTrue(message { SqlIdentifierValidator.validateTempTableName("") }.contains("cannot be blank"))
    }

    @Test
    fun `validateIdentifier blank rejected with blank-specific message`() {
        assertTrue(message { SqlIdentifierValidator.validateIdentifier("") }.contains("cannot be blank"))
    }

    @Test
    fun `validateImportTableName blank rejected with blank-specific message`() {
        assertTrue(message { SqlIdentifierValidator.validateImportTableName("") }.contains("cannot be blank"))
    }

    // ---- case normalization (kills VoidMethodCall on uppercase()/lowercase()) ----

    @Test
    fun `validateIdentifier rejects a lowercase SQL keyword - uppercase normalization must run`() {
        // "select" only matches the uppercase SQL_RESERVED_KEYWORDS set after .uppercase();
        // drop that call and the lowercase form is accepted -> this assertion fails -> mutant killed.
        assertTrue(message { SqlIdentifierValidator.validateIdentifier("select") }.contains("reserved SQL keyword"))
    }

    @Test
    fun `validateImportTableName rejects an upper-case system catalog - lowercase normalization must run`() {
        // "PG_catalog" matches the identifier pattern, then only trips the "pg_" system-catalog
        // guard after .lowercase(); drop that call and it is accepted -> assertion fails -> mutant killed.
        assertTrue(
            message { SqlIdentifierValidator.validateImportTableName("PG_catalog") }
                .contains("reserved system catalog")
        )
    }

    // ---- happy paths (pin the accepted-identifier return value) ----

    @Test
    fun `validateIdentifier accepts a normal snake_case identifier unchanged`() {
        assertEquals("study_participants", SqlIdentifierValidator.validateIdentifier("study_participants"))
    }

    @Test
    fun `validateImportTableName accepts a schema-qualified name unchanged`() {
        assertEquals("src.system_apps", SqlIdentifierValidator.validateImportTableName("src.system_apps"))
    }
}
