package com.openlattice.chronicle.services.security

import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant

class EncryptionHealthServiceTest {

    @Test
    fun testEmptyEncryptionStatusFailsClosed() {
        val service = EncryptionHealthService(Mockito.mock(StorageResolver::class.java))

        val response = service.encryptionHealth()

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertEquals("FAIL", response.body?.get("status"))
        assertTrue(response.body?.containsKey("last_error") == true)
        assertEquals("unknown", response.body?.get("tde_extension_version"))
        assertEquals(false, response.body?.get("ssl_active"))
        assertEquals("never", response.body?.get("last_check"))
        assertEquals(emptyMap<String, Boolean>(), response.body?.get("tables"))
    }

    @Test
    fun testSuccessfulEvidenceReturnsOk() {
        val service = service(healthyPool())
        val startedAt = Instant.now()

        service.onStartup()

        val finishedAt = Instant.now()
        val response = service.encryptionHealth()
        val body = requireNotNull(response.body)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("PASS", body["status"])
        assertEquals("1.0.0", body["tde_extension_version"])
        assertEquals(true, body["ssl_active"])
        assertEquals(mapOf("participants" to true), body["tables"])
        assertFalse(body.containsKey("last_error"))
        assertFalse(body.containsKey("failed_tables"))
        val checkedAt = Instant.parse(body.getValue("last_check") as String)
        assertTrue(checkedAt >= startedAt && checkedAt <= finishedAt)
    }

    @Test
    fun testStaleTableStatusDoesNotMaskLastCheckFailure() {
        val pool = healthyPool()
        val service = service(pool)
        service.onStartup()
        val healthy = service.encryptionHealth()
        assertEquals(HttpStatus.OK, healthy.statusCode)
        assertEquals("PASS", healthy.body?.get("status"))
        Mockito.`when`(pool.connection).thenThrow(SQLException("database unavailable", "08006"))

        service.periodicCheck()

        val response = service.encryptionHealth()
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertEquals("FAIL", response.body?.get("status"))
        assertEquals("database unavailable", response.body?.get("last_error"))
        assertEquals("1.0.0", response.body?.get("tde_extension_version"))
        assertEquals(true, response.body?.get("ssl_active"))
        assertEquals(mapOf("participants" to true), response.body?.get("tables"))
        assertEquals(healthy.body?.get("last_check"), response.body?.get("last_check"))
    }

    private fun service(pool: HikariDataSource): EncryptionHealthService {
        val storage = Mockito.mock(StorageResolver::class.java)
        Mockito.`when`(storage.getPlatformStorage()).thenReturn(pool)
        return EncryptionHealthService(storage)
    }

    private fun healthyPool(): HikariDataSource {
        val pool = Mockito.mock(HikariDataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        val statement = Mockito.mock(Statement::class.java)
        val version = Mockito.mock(ResultSet::class.java)
        val ssl = Mockito.mock(ResultSet::class.java)
        val tables = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(pool.connection).thenReturn(connection)
        Mockito.`when`(connection.createStatement()).thenReturn(statement)
        Mockito.`when`(statement.executeQuery(Mockito.contains("FROM pg_extension"))).thenReturn(version)
        Mockito.`when`(statement.executeQuery(Mockito.contains("FROM pg_stat_ssl"))).thenReturn(ssl)
        Mockito.`when`(statement.executeQuery(Mockito.contains("FROM pg_class"))).thenReturn(tables)
        Mockito.`when`(version.next()).thenReturn(true, false)
        Mockito.`when`(version.getString(1)).thenReturn("1.0.0")
        Mockito.`when`(ssl.next()).thenReturn(true, false)
        Mockito.`when`(ssl.getBoolean(1)).thenReturn(true)
        Mockito.`when`(tables.next()).thenReturn(true, false)
        Mockito.`when`(tables.getString(1)).thenReturn("participants")
        Mockito.`when`(tables.getBoolean(2)).thenReturn(true)
        return pool
    }
}
