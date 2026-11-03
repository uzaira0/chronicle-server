package com.openlattice.chronicle.storage.rls

import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import java.sql.Connection
import org.flywaydb.core.api.FlywayException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaEncodingContractTest {
    @Test
    fun nonUtf8DatabaseCannotAcceptTheUnicodeSchemaContract() {
        ChronicleContractTestSchema.prodPostgresContainer("chronicle_encoding_contract").apply {
            withEnv("POSTGRES_INITDB_ARGS", "--encoding=SQL_ASCII")
        }.use { postgres ->
            postgres.start()
            ChronicleContractTestSchema.waitForQueryReady(postgres)
            postgres.createConnection("").use { connection ->
                assertNonUtf8Encoding(connection)
                ChronicleContractTestSchema.applyFrameworkSchema(connection)
            }
            val failure = assertThrows(FlywayException::class.java) {
                ChronicleContractTestSchema.migrate(postgres)
            }
            assertTrue("Migration must explain the encoding requirement: ${failure.message}",
                failure.message.orEmpty().contains("Chronicle requires UTF8 database encoding"))
        }
    }

    private fun assertNonUtf8Encoding(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeQuery("SHOW server_encoding").use { rows ->
                assertTrue(rows.next())
                assertEquals("SQL_ASCII", rows.getString(1))
            }
        }
    }
}
