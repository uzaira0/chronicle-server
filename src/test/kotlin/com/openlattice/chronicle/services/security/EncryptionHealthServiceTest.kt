package com.openlattice.chronicle.services.security

import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import java.util.concurrent.ConcurrentHashMap

class EncryptionHealthServiceTest {

    @Test
    fun testEmptyEncryptionStatusFailsClosed() {
        val service = EncryptionHealthService(Mockito.mock(StorageResolver::class.java))

        val response = service.encryptionHealth()

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertEquals("FAIL", response.body?.get("status"))
        assertTrue(response.body?.containsKey("last_error") == true)
    }

    @Test
    fun testSuccessfulEvidenceReturnsOk() {
        val service = EncryptionHealthService(Mockito.mock(StorageResolver::class.java))
        setField(service, "tdeVersion", "1.0.0")
        setField(service, "lastCheckError", null)
        @Suppress("UNCHECKED_CAST")
        val tableStatus = getField(service, "tableStatus") as ConcurrentHashMap<String, Boolean>
        tableStatus["participants"] = true

        val response = service.encryptionHealth()

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("PASS", response.body?.get("status"))
    }

    @Test
    fun testStaleTableStatusDoesNotMaskLastCheckFailure() {
        val service = EncryptionHealthService(Mockito.mock(StorageResolver::class.java))
        setField(service, "tdeVersion", "1.0.0")
        setField(service, "lastCheckError", "database unavailable")
        @Suppress("UNCHECKED_CAST")
        val tableStatus = getField(service, "tableStatus") as ConcurrentHashMap<String, Boolean>
        tableStatus["participants"] = true

        val response = service.encryptionHealth()

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertEquals("FAIL", response.body?.get("status"))
        assertEquals("database unavailable", response.body?.get("last_error"))
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun getField(target: Any, name: String): Any? {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)
    }
}
