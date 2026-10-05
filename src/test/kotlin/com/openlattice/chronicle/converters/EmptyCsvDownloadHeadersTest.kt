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
import java.util.function.Supplier

class EmptyCsvDownloadHeadersTest {
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
