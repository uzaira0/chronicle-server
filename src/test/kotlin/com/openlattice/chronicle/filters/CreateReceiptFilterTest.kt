package com.openlattice.chronicle.filters

import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletResponse
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

class CreateReceiptFilterTest {
    @Test
    fun malformedCreateKeysAreRejectedBeforeReceiptStorage() {
        val storage = mock(StorageResolver::class.java)
        val authorization = mock(StudyAuthorizationService::class.java)
        for (key in listOf("", "has spaces", "x".repeat(129))) {
            val request = MockHttpServletRequest("POST", "/chronicle/v3/study").apply {
                addHeader("Idempotency-Key", key)
            }
            val response = MockHttpServletResponse()
            CreateReceiptFilter(storage, authorization) { throw AssertionError("Invalid key needs no secret") }
                .doFilter(request, response, FilterChain { _, _ -> fail("Invalid keys must not create") })
            assertEquals(400, response.status)
        }
        verifyNoInteractions(storage, authorization)
    }

    @Test
    fun keylessCreatesKeepLegacyBehaviorWithoutReceiptStorageOrSecret() {
        val storage = mock(StorageResolver::class.java)
        val authorization = mock(StudyAuthorizationService::class.java)
        val study = UUID.randomUUID()
        for (route in listOf("/v3/study", "/v3/survey/$study/questionnaire", "/v3/study/$study/export/async", "/v3/study/$study/api-keys")) {
            val filter = CreateReceiptFilter(storage, authorization) { throw AssertionError("Keyless creates need no receipt secret") }
            var created = 0
            repeat(2) {
                val request = MockHttpServletRequest("POST", "/chronicle$route").apply {
                    setContent("{\"title\":\"legacy create\"}".toByteArray())
                }
                val response = MockHttpServletResponse()
                filter.doFilter(request, response, FilterChain { req, res ->
                    assertSame(request, req)
                    assertEquals("{\"title\":\"legacy create\"}", req.reader.readText())
                    created++
                    (res as HttpServletResponse).apply {
                        status = 201
                        writer.write("created-$created")
                    }
                })
                assertEquals(route, 201, response.status)
                assertEquals("created-${it + 1}", response.contentAsString)
                assertNull(response.getHeader("Idempotency-Replayed"))
            }
            assertEquals(2, created)
        }
        verifyNoInteractions(storage, authorization)
    }

    @Test
    fun lostCreateResponsesReplayAcrossFilterInstancesWithoutIssuingAnotherCredential() {
        TestSecurityUtils.setupSecurityContext(subject = "receipt-owner", admin = false)
        try {
            val study = UUID.randomUUID()
            for (route in listOf("/v3/study", "/v3/survey/$study/questionnaire", "/v3/study/$study/export/async", "/v3/study/$study/api-keys")) {
                val storage = mock(StorageResolver::class.java)
                val pool = mock(HikariDataSource::class.java)
                val connection = mock(Connection::class.java)
                `when`(storage.getPlatformStorage()).thenReturn(pool)
                `when`(pool.connection).thenReturn(connection)
                var requestHash: ByteArray? = null
                var sealed: String? = null
                `when`(connection.prepareStatement(anyString())).thenAnswer { call ->
                    val sql = call.getArgument<String>(0)
                    val params = mutableMapOf<Int, Any?>()
                    val statement = mock(PreparedStatement::class.java)
                    doAnswer { params[it.getArgument<Int>(0)] = it.getArgument<Any?>(1); null }.`when`(statement).setString(anyInt(), any())
                    doAnswer { params[it.getArgument<Int>(0)] = it.getArgument<ByteArray>(1); null }.`when`(statement).setBytes(anyInt(), any())
                    `when`(statement.executeUpdate()).thenAnswer {
                        if (sql.startsWith("INSERT")) {
                            if (requestHash != null) 0 else { requestHash = params[4] as ByteArray; 1 }
                        } else { sealed = params[3] as String; 1 }
                    }
                    val rows = mock(ResultSet::class.java)
                    `when`(rows.next()).thenReturn(true)
                    `when`(rows.getBytes("request_hash")).thenAnswer { requestHash }
                    `when`(rows.getString("sealed_response")).thenAnswer { sealed }
                    `when`(rows.getInt("response_status")).thenReturn(201)
                    `when`(rows.getString("response_type")).thenReturn("application/json")
                    `when`(statement.executeQuery()).thenReturn(rows)
                    statement
                }
                fun request(body: String = "{\"title\":\"same logical create\"}") = MockHttpServletRequest("POST", "/chronicle$route").apply {
                    addHeader("Idempotency-Key", "stable-intent-key")
                    setContent(body.toByteArray())
                }
                var created = 0
                val action = FilterChain { req, res ->
                    assertEquals("{\"title\":\"same logical create\"}", req.reader.readText())
                    created++
                    (res as HttpServletResponse).apply {
                        status = 201; contentType = "application/json"
                        writer.write("{\"result\":\"raw-key-sentinel-$created\"}")
                    }
                }
                val authorization = mock(StudyAuthorizationService::class.java)
                val first = MockHttpServletResponse()
                CreateReceiptFilter(storage, authorization) { "test-receipt-secret-32-characters-minimum" }.doFilter(request(), first, action)
                val replay = MockHttpServletResponse()
                CreateReceiptFilter(storage, authorization) { "test-receipt-secret-32-characters-minimum" }.doFilter(request(), replay, action)
                assertEquals(route, 1, created)
                assertEquals(first.contentAsString, replay.contentAsString)
                assertEquals("true", replay.getHeader("Idempotency-Replayed"))
                assertFalse(sealed.orEmpty().contains("raw-key-sentinel"))
                val conflict = MockHttpServletResponse()
                CreateReceiptFilter(storage, authorization) { "test-receipt-secret-32-characters-minimum" }
                    .doFilter(request("{\"title\":\"changed\"}"), conflict, action)
                assertEquals(409, conflict.status)
                assertEquals(1, created)
                if (route != "/v3/study") {
                    doThrow(com.geekbeast.controllers.exceptions.ForbiddenException("private access diagnostic")).`when`(authorization)
                        .requirePermission(study, if (route.endsWith("/export/async")) com.openlattice.chronicle.authorization.StudyPermission.EXPORT_DATA
                            else com.openlattice.chronicle.authorization.StudyPermission.MODIFY_STUDY)
                    val denied = MockHttpServletResponse()
                    CreateReceiptFilter(storage, authorization) { "test-receipt-secret-32-characters-minimum" }.doFilter(request(), denied, action)
                    assertEquals(403, denied.status)
                    assertFalse(denied.contentAsString.contains("private access diagnostic"))
                    assertEquals(1, created)
                }
            }
        } finally { TestSecurityUtils.clearSecurityContext() }
    }
}
