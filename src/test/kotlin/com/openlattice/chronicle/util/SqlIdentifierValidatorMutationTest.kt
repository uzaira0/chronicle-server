package com.openlattice.chronicle.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Deterministic, example-based tests for [SqlIdentifierValidator] that pin every
 * accept/reject branch and length boundary so PIT mutants are killed.
 */
class SqlIdentifierValidatorMutationTest {

    private fun expectInvalid(block: () -> Unit) =
        assertThrows(SqlIdentifierValidator.InvalidSqlIdentifierException::class.java) { block() }

    // ---- validateTableName ----

    @Test
    fun `validateTableName accepts an allowlisted table and returns it unchanged`() {
        assertEquals("studies", SqlIdentifierValidator.validateTableName("studies"))
        assertEquals("candidates", SqlIdentifierValidator.validateTableName("candidates"))
    }

    @Test
    fun `validateTableName rejects blank`() {
        expectInvalid { SqlIdentifierValidator.validateTableName("   ") }
    }

    @Test
    fun `validateTableName rejects unknown table`() {
        expectInvalid { SqlIdentifierValidator.validateTableName("not_a_real_table") }
    }

    // ---- validateTempTableName ----

    @Test
    fun `validateTempTableName accepts a valid prefixed name unchanged`() {
        assertEquals("temp_abc123", SqlIdentifierValidator.validateTempTableName("temp_abc123"))
        assertEquals("duplicate_events_X1", SqlIdentifierValidator.validateTempTableName("duplicate_events_X1"))
    }

    @Test
    fun `validateTempTableName rejects blank`() {
        expectInvalid { SqlIdentifierValidator.validateTempTableName("   ") }
    }

    @Test
    fun `validateTempTableName at exactly 63 chars is accepted`() {
        val name = "temp_" + "a".repeat(63 - "temp_".length) // length == 63 boundary
        assertEquals(63, name.length)
        assertEquals(name, SqlIdentifierValidator.validateTempTableName(name))
    }

    @Test
    fun `validateTempTableName over 63 chars is rejected`() {
        val name = "temp_" + "a".repeat(63) // length == 68 > 63
        expectInvalid { SqlIdentifierValidator.validateTempTableName(name) }
    }

    @Test
    fun `validateTempTableName without allowed prefix is rejected`() {
        expectInvalid { SqlIdentifierValidator.validateTempTableName("evil_table_abc") }
    }

    @Test
    fun `validateTempTableName with invalid characters after prefix is rejected`() {
        expectInvalid { SqlIdentifierValidator.validateTempTableName("temp_abc-def") }
    }

    // ---- validateIdentifier ----

    @Test
    fun `validateIdentifier accepts a valid identifier unchanged`() {
        assertEquals("my_column1", SqlIdentifierValidator.validateIdentifier("my_column1"))
        assertEquals("_underscore", SqlIdentifierValidator.validateIdentifier("_underscore"))
    }

    @Test
    fun `validateIdentifier rejects blank`() {
        expectInvalid { SqlIdentifierValidator.validateIdentifier("  ") }
    }

    @Test
    fun `validateIdentifier at exactly 63 chars is accepted`() {
        val id = "a".repeat(63)
        assertEquals(id, SqlIdentifierValidator.validateIdentifier(id))
    }

    @Test
    fun `validateIdentifier over 63 chars is rejected`() {
        expectInvalid { SqlIdentifierValidator.validateIdentifier("a".repeat(64)) }
    }

    @Test
    fun `validateIdentifier rejects names starting with a digit`() {
        expectInvalid { SqlIdentifierValidator.validateIdentifier("1column") }
    }

    @Test
    fun `validateIdentifier rejects injection metacharacters`() {
        expectInvalid { SqlIdentifierValidator.validateIdentifier("col; DROP TABLE x") }
    }

    @Test
    fun `validateIdentifier rejects reserved SQL keyword (case-insensitive)`() {
        expectInvalid { SqlIdentifierValidator.validateIdentifier("select") }
        expectInvalid { SqlIdentifierValidator.validateIdentifier("DROP") }
    }

    @Test
    fun `validateIdentifier accepts a non-keyword that resembles one`() {
        assertEquals("selection", SqlIdentifierValidator.validateIdentifier("selection"))
    }

    // ---- validateImportTableName ----

    @Test
    fun `validateImportTableName accepts bare and schema-qualified names`() {
        assertEquals("system_apps", SqlIdentifierValidator.validateImportTableName("system_apps"))
        assertEquals("src.system_apps", SqlIdentifierValidator.validateImportTableName("src.system_apps"))
    }

    @Test
    fun `validateImportTableName rejects blank`() {
        expectInvalid { SqlIdentifierValidator.validateImportTableName("   ") }
    }

    @Test
    fun `validateImportTableName at exactly 255 chars is accepted`() {
        val name = "a".repeat(255)
        assertEquals(name, SqlIdentifierValidator.validateImportTableName(name))
    }

    @Test
    fun `validateImportTableName over 255 chars is rejected`() {
        expectInvalid { SqlIdentifierValidator.validateImportTableName("a".repeat(256)) }
    }

    @Test
    fun `validateImportTableName rejects more than one qualifier`() {
        expectInvalid { SqlIdentifierValidator.validateImportTableName("a.b.c") }
    }

    @Test
    fun `validateImportTableName rejects invalid segment`() {
        expectInvalid { SqlIdentifierValidator.validateImportTableName("src.bad-name") }
    }

    @Test
    fun `validateImportTableName rejects reserved system catalogs`() {
        expectInvalid { SqlIdentifierValidator.validateImportTableName("information_schema.columns") }
        expectInvalid { SqlIdentifierValidator.validateImportTableName("pg_catalog") }
    }

    // ---- quoteIdentifier ----

    @Test
    fun `quoteIdentifier validates then double-quotes the identifier`() {
        assertEquals("\"my_col\"", SqlIdentifierValidator.quoteIdentifier("my_col"))
    }

    @Test
    fun `quoteIdentifier rejects an invalid identifier`() {
        expectInvalid { SqlIdentifierValidator.quoteIdentifier("bad-col") }
    }

    // ---- validateTimeout ----

    @Test
    fun `validateTimeout accepts boundary values`() {
        assertEquals(0L, SqlIdentifierValidator.validateTimeout(0L))
        assertEquals(3600000L, SqlIdentifierValidator.validateTimeout(3600000L))
    }

    @Test
    fun `validateTimeout rejects negative`() {
        assertThrows(IllegalArgumentException::class.java) { SqlIdentifierValidator.validateTimeout(-1L) }
    }

    @Test
    fun `validateTimeout rejects over one hour`() {
        assertThrows(IllegalArgumentException::class.java) { SqlIdentifierValidator.validateTimeout(3600001L) }
    }
}
