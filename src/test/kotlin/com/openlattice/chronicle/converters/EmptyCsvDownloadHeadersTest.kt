package com.openlattice.chronicle.converters

import com.geekbeast.postgres.streams.BasePostgresIterable
import com.geekbeast.postgres.streams.StatementHolder
import com.openlattice.chronicle.constants.CustomMediaType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.springframework.mock.http.MockHttpOutputMessage
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.time.OffsetDateTime
import java.util.function.Supplier

class EmptyCsvDownloadHeadersTest {
    private fun postgresRows(vararg rows: Map<String, Any>): BasePostgresIterable<Map<String, Any>> {
        val resultSet = Mockito.mock(ResultSet::class.java)
        val remaining = rows.iterator()
        Mockito.`when`(resultSet.next()).thenAnswer { remaining.hasNext() }
        return BasePostgresIterable(
            Supplier {
                StatementHolder(Mockito.mock(Connection::class.java), Mockito.mock(Statement::class.java), resultSet)
            },
        ) { remaining.next() }
    }

    @Test
    fun currentDownloadsKeepDateAndQuotingSerialization() {
        val timestamp = OffsetDateTime.parse("2026-09-01T12:00:00-03:00")
        val row = linkedMapOf<String, Any>("timestamp" to timestamp, "text" to "hello, \"quoted\"")
        val columns = row.keys.toList()
        val current = PostgresDownloadWrapper(postgresRows(row)).withColumnAdvice(columns)
        val currentOutput = MockHttpOutputMessage()

        PostgresDownloadCsvHttpMessageConverter().write(current, current::class.java, CustomMediaType.TEXT_CSV, currentOutput)

        val expected = "timestamp,text\n2026-09-01T12:00:00-03:00,\"hello, \"\"quoted\"\"\"\n"
        assertEquals(expected, currentOutput.bodyAsString)
    }

    @Test
    fun emptyTimeUseDiaryAndPostgresDownloadsWriteHeaders() {
        val tudResultSet = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(tudResultSet.next()).thenReturn(false)
        val tudRows = BasePostgresIterable(
            Supplier {
                StatementHolder(
                    Mockito.mock(Connection::class.java),
                    Mockito.mock(Statement::class.java),
                    tudResultSet,
                )
            },
        ) { emptyList<Map<String, Any>>() }
        val tud = TimeUseDiaryPostgresDownloadWrapper(tudRows).withColumnAdvice(listOf("tud_column"))
        val tudOutput = MockHttpOutputMessage()
        TimeUseDiaryDownloadCsvHttpMessageConverter().write(
            tud,
            tud::class.java,
            CustomMediaType.TEXT_CSV,
            tudOutput,
        )

        val postgresResultSet = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(postgresResultSet.next()).thenReturn(false)
        val postgresRows = BasePostgresIterable(
            Supplier {
                StatementHolder(
                    Mockito.mock(Connection::class.java),
                    Mockito.mock(Statement::class.java),
                    postgresResultSet,
                )
            },
        ) { emptyMap<String, Any>() }
        val postgres = PostgresDownloadWrapper(postgresRows).withColumnAdvice(listOf("postgres_column"))
        val postgresOutput = MockHttpOutputMessage()
        PostgresDownloadCsvHttpMessageConverter().write(
            postgres,
            postgres::class.java,
            CustomMediaType.TEXT_CSV,
            postgresOutput,
        )

        assertEquals("tud_column", tudOutput.bodyAsString.trim())
        assertEquals("postgres_column", postgresOutput.bodyAsString.trim())
    }
}
