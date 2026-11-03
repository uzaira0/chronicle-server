package com.openlattice.chronicle.converters

import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.postgres.streams.BasePostgresIterable
import com.geekbeast.postgres.streams.StatementHolder
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.mock.http.MockHttpOutputMessage
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.util.function.Supplier

class JsonDownloadStreamTest {
    @Test
    fun participantDownloadIsOneJsonArrayAndReleasesItsCursor() {
        val connection = Mockito.mock(Connection::class.java)
        val resultSet = Mockito.mock(ResultSet::class.java)
        val rowCount = 25 * 31
        var fetched = 0
        Mockito.`when`(resultSet.next()).thenAnswer { ++fetched <= rowCount }
        val output = MockHttpOutputMessage()
        var row = 0
        val download = PostgresDownloadWrapper(BasePostgresIterable(
            Supplier { StatementHolder(connection, Mockito.mock(Statement::class.java), resultSet) },
        ) {
            if (row > 0) assertTrue("previous row must be written before fetching another",
                output.bodyAsString.contains("\"row$row\""))
            mapOf("value" to "row${++row}")
        })

        PostgresDownloadJsonHttpMessageConverter(ObjectMappers.newJsonMapper())
            .write(download, download::class.java, MediaType.APPLICATION_JSON, output)

        assertEquals(rowCount, ObjectMappers.newJsonMapper().readTree(output.bodyAsString).size())
        Mockito.verify(connection).close()
    }
}
