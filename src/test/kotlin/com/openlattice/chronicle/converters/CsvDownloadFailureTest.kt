package com.openlattice.chronicle.converters

import com.geekbeast.postgres.streams.BasePostgresIterable
import com.geekbeast.postgres.streams.StatementHolder
import com.openlattice.chronicle.constants.CustomMediaType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpOutputMessage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.util.function.Supplier

class CsvDownloadFailureTest {
    private class RecordingOutput(private val failWrites: Boolean = false) : HttpOutputMessage {
        var closed = false
        private val headers = HttpHeaders()
        private val stream = object : OutputStream() {
            private val sink = ByteArrayOutputStream()
            override fun write(b: Int) {
                if (failWrites) throw IOException("client went away")
                sink.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (failWrites) throw IOException("client went away")
                sink.write(b, off, len)
            }

            override fun close() {
                closed = true
            }
        }

        override fun getBody(): OutputStream = stream
        override fun getHeaders(): HttpHeaders = headers
    }

    private fun rows(connection: Connection, resultSet: ResultSet, mapper: (ResultSet) -> Map<String, Any>) =
        PostgresDownloadWrapper(
            BasePostgresIterable(
                Supplier { StatementHolder(connection, Mockito.mock(Statement::class.java), resultSet) },
                mapper,
            ),
        ).withColumnAdvice(listOf("value"))

    // Regression V-2: a failure in the middle of the stream was closed into a clean 200 CSV that
    // looked complete. The writer must stay unclosed so the response is aborted, not terminated.
    @Test
    fun midStreamFailureDoesNotFinalizeTheResponse() {
        val resultSet = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(resultSet.next()).thenReturn(true)
        var calls = 0
        val download = rows(Mockito.mock(Connection::class.java), resultSet) {
            check(++calls < 3) { "legacy row cannot be mapped" }
            mapOf("value" to "row$calls")
        }
        val output = RecordingOutput()

        val failed = runCatching {
            PostgresDownloadCsvHttpMessageConverter().write(download, download::class.java, CustomMediaType.TEXT_CSV, output)
        }.isFailure

        assertTrue(failed)
        assertFalse("a failed download must not end as a cleanly closed CSV", output.closed)
    }

    // Regression V-28: a client abort left the unexhausted iterator, and its pooled connection,
    // checked out until the watchdog expired it.
    @Test
    fun clientAbortReleasesTheConnectionImmediately() {
        val resultSet = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(resultSet.next()).thenReturn(true)
        val connection = Mockito.mock(Connection::class.java)
        val download = rows(connection, resultSet) { mapOf("value" to "x".repeat(1024)) }

        val failed = runCatching {
            PostgresDownloadCsvHttpMessageConverter().write(
                download,
                download::class.java,
                CustomMediaType.TEXT_CSV,
                RecordingOutput(failWrites = true),
            )
        }.isFailure

        assertTrue(failed)
        Mockito.verify(connection).close()
    }
}
