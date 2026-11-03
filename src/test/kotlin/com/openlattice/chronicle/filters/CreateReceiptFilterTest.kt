package com.openlattice.chronicle.filters

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.controllers.TestSecurityUtils
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.File
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

class CreateReceiptFilterTest {
    private fun createRoutes(study: UUID) = listOf("/v3/study", "/v3/survey/$study/questionnaire",
        "/v3/study/$study/api-keys", "/v3/study/$study/webhooks", "/v3/study/$study/clone",
        "/v3/study/$study/api-keys/${UUID.randomUUID()}/rotate", "/v3/organization", "/v3/candidate")

    /** Generated clients read the spec, so every documented create this filter guards must declare the header. */
    @Test
    fun everyDocumentedGuardedCreateDeclaresTheIdempotencyKey() {
        val spec = ObjectMapper(YAMLFactory()).readTree(File("../chronicle-api/chronicle.yaml"))
        val study = UUID.randomUUID()
        val undeclared = spec["paths"].fields().asSequence().mapNotNull { (path, operations) ->
            val post = operations["post"] ?: return@mapNotNull null
            val route = path.removePrefix("/chronicle").replace(Regex("\\{[^}]+}"), study.toString())
            val declared = post.path("parameters").any {
                it.path("\$ref").asText() == "#/components/parameters/idempotencyKey"
            }
            path.takeIf { CreateReceiptFilter.createRoute(route) != null && !declared }
        }.toList()
        assertEquals(emptyList<String>(), undeclared)
        assertEquals(ParticipantFormAccessFilter.IDEMPOTENCY_HEADER,
            spec["components"]["parameters"]["idempotencyKey"]["name"].asText())
    }

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
            assertEquals("application/json", response.contentType)
            assertFilterError(response, 400, "Idempotency-Key must contain 1–128 letters, digits, underscores or hyphens")
        }
        verifyNoInteractions(storage, authorization)
    }

    @Test
    fun keylessCreatesRequireAKeyBeforeReceiptStorageOrSecret() {
        val storage = mock(StorageResolver::class.java)
        val authorization = mock(StudyAuthorizationService::class.java)
        val study = UUID.randomUUID()
        for (route in createRoutes(study)) {
            val filter = CreateReceiptFilter(storage, authorization) { throw AssertionError("Keyless creates need no receipt secret") }
            var created = 0
            repeat(2) {
                val request = MockHttpServletRequest("POST", "/chronicle$route")
                val response = MockHttpServletResponse()
                filter.doFilter(request, response, FilterChain { _, _ -> created++ })
                assertEquals(route, 428, response.status)
            }
            assertEquals(0, created)
        }
        verifyNoInteractions(storage, authorization)
    }

    @Test
    fun lostCreateResponsesReplayAcrossFilterInstancesWithoutIssuingAnotherCredential() {
        TestSecurityUtils.setupSecurityContext(subject = "receipt-owner", admin = false)
        setReceiptContext()
        try {
            val study = UUID(0x0000000a000b000cL, 0x000d00000000000eL)
            for (route in createRoutes(study)) {
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
                val shortStudy = study.toString().split('-').joinToString("-") { it.trimStart('0').ifEmpty { "0" } }
                val replayFilter = CreateReceiptFilter(storage, authorization) { "test-receipt-secret-32-characters-minimum" }
                for (spelling in listOf(study.toString().replace("-", "%2d"), study.toString().uppercase(), shortStudy, "%20$study%20")) {
                    assertReplay(replayFilter, request(), first, action, "/chronicle${route.replace(study.toString(), spelling)}")
                }
                assertEquals(route, 1, created)
                assertFalse(sealed.orEmpty().contains("raw-key-sentinel"))
                val unavailableReplay = MockHttpServletResponse()
                CreateReceiptFilter(storage, authorization) { "different-receipt-secret-32-characters-minimum" }
                    .doFilter(request(), unavailableReplay, action)
                assertEquals(409, unavailableReplay.status)
                assertFilterError(unavailableReplay, 409, "This create already completed; its original result cannot be replayed")
                val conflict = MockHttpServletResponse()
                CreateReceiptFilter(storage, authorization) { "test-receipt-secret-32-characters-minimum" }
                    .doFilter(request("{\"title\":\"changed\"}"), conflict, action)
                assertEquals(409, conflict.status)
                assertFilterError(conflict, 409, "Idempotency-Key was already used for a different request")
                assertEquals(1, created)
                if (route.contains(study.toString())) {
                    doThrow(com.geekbeast.controllers.exceptions.ForbiddenException("private access diagnostic")).`when`(authorization)
                        .requirePermission(study, com.openlattice.chronicle.authorization.StudyPermission.MODIFY_STUDY)
                    val denied = MockHttpServletResponse()
                    CreateReceiptFilter(storage, authorization) { "test-receipt-secret-32-characters-minimum" }.doFilter(request(), denied, action)
                    assertEquals(403, denied.status)
                    assertFilterError(denied, 403, "Access denied")
                    assertFalse(denied.contentAsString.contains("private access diagnostic"))
                    assertEquals(1, created)
                }
            }
        } finally {
            com.openlattice.chronicle.storage.rls.RLSRequestContext.clear()
            TestSecurityUtils.clearSecurityContext()
        }
    }

    private fun assertReplay(
        filter: CreateReceiptFilter,
        request: MockHttpServletRequest,
        expected: MockHttpServletResponse,
        action: FilterChain,
        requestUri: String,
    ) {
        request.requestURI = requestUri
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, action)
        assertEquals(expected.contentAsString, response.contentAsString)
        assertEquals("true", response.getHeader(CreateReceiptFilter.REPLAYED_HEADER))
    }

    @Test
    fun failedCreatesRollBackBeforeSendingTheOriginalResponse() {
        TestSecurityUtils.setupSecurityContext(subject = "receipt-owner", admin = false)
        setReceiptContext()
        try {
            val storage = mock(StorageResolver::class.java)
            val pool = mock(HikariDataSource::class.java)
            val connection = mock(Connection::class.java)
            val statement = mock(PreparedStatement::class.java)
            `when`(storage.getPlatformStorage()).thenReturn(pool)
            `when`(pool.connection).thenReturn(connection)
            `when`(connection.prepareStatement(anyString())).thenReturn(statement)
            `when`(statement.executeUpdate()).thenReturn(1)
            val request = MockHttpServletRequest("POST", "/chronicle/v3/study").apply {
                addHeader("Idempotency-Key", "failed-intent")
                setContent("{}".toByteArray())
            }
            val response = MockHttpServletResponse()
            CreateReceiptFilter(storage, mock(StudyAuthorizationService::class.java)) {
                "test-receipt-secret-32-characters-minimum"
            }.doFilter(request, response, FilterChain { _, result ->
                assertEquals("response remains buffered until rollback", "", response.contentAsString)
                (result as HttpServletResponse).apply {
                    status = 422
                    contentType = "application/json"
                    writer.write("""{"message":"invalid create"}""")
                }
            })
            assertEquals(422, response.status)
            assertEquals("""{"message":"invalid create"}""", response.contentAsString)
            verify(connection).rollback()
            verify(connection, never()).commit()
            verify(statement).executeUpdate()
        } finally {
            com.openlattice.chronicle.storage.rls.RLSRequestContext.clear()
            TestSecurityUtils.clearSecurityContext()
        }
    }

    private fun setReceiptContext() {
        val authorization = mock(com.openlattice.chronicle.authorization.AuthorizationManager::class.java)
        `when`(authorization.getAuthorizedObjectsOfType(
            org.mockito.kotlin.any<Set<com.openlattice.chronicle.authorization.Principal>>(),
            org.mockito.kotlin.any<com.openlattice.chronicle.authorization.SecurableObjectType>(),
            org.mockito.kotlin.any<java.util.EnumSet<com.openlattice.chronicle.authorization.Permission>>(),
        )).thenAnswer { java.util.stream.Stream.empty<com.openlattice.chronicle.authorization.AclKey>() }
        com.openlattice.chronicle.storage.rls.RLSRequestContext.set(
            com.openlattice.chronicle.storage.rls.RLSContextManager(authorization).getCurrentUserContext(),
        )
    }
}
