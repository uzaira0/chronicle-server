package com.openlattice.chronicle.filters

import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariDataSource
import jakarta.servlet.FilterChain
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

class ConcurrentEditFilterTest {
    @Test fun annotationTabsCannotOverwriteTheFirstSave() = twoTabs("participant/P-1/annotations")
    @Test fun questionnaireTabsCannotOverwriteTheFirstSave() = twoTabs("questionnaire/${UUID.randomUUID()}")

    @Test fun participantQuestionnaireReadDoesNotRequireResearcherAcl() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext()
        val study = UUID.randomUUID()
        val questionnaire = UUID.randomUUID()
        val service = mock(com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService::class.java)
        val scope = com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessScope(
            UUID.randomUUID(), study, "participant", com.openlattice.chronicle.participantaccess.ParticipantFormKind.QUESTIONNAIRE,
            questionnaire, null, java.time.OffsetDateTime.now().plusHours(1),
        )
        `when`(service.resolveSession("synthetic-participant-session", null, false)).thenReturn(scope)
        val authorization = mock(StudyAuthorizationService::class.java)
        doThrow(com.geekbeast.controllers.exceptions.ForbiddenException("No researcher ACL"))
            .`when`(authorization).requirePermission(study, com.openlattice.chronicle.authorization.StudyPermission.READ_STUDY)
        doThrow(com.geekbeast.controllers.exceptions.ForbiddenException("No researcher ACL"))
            .`when`(authorization).requirePermission(study, com.openlattice.chronicle.authorization.StudyPermission.MODIFY_STUDY)
        val storage = mock(StorageResolver::class.java)
        val edits = ConcurrentEditFilter(storage, authorization)
        val participants = ParticipantFormAccessFilter(service)
        val response = MockHttpServletResponse()
        val request = MockHttpServletRequest("GET", "/chronicle/v3/survey/$study/questionnaire/$questionnaire").apply {
            setCookies(jakarta.servlet.http.Cookie(ParticipantFormAccessFilter.SESSION_COOKIE, "synthetic-participant-session"))
        }
        participants.doFilter(request, response, FilterChain { req, res ->
            edits.doFilter(req, res, FilterChain { _, result -> result.writer.write("questionnaire") })
        })
        assertEquals(200, response.status)
        assertEquals("questionnaire", response.contentAsString)
        verifyNoInteractions(storage, authorization)
        val patch = MockHttpServletResponse()
        edits.doFilter(MockHttpServletRequest("PATCH", request.requestURI), patch, FilterChain { _, _ -> fail("Participant cannot edit") })
        assertEquals(403, patch.status)
        org.springframework.security.core.context.SecurityContextHolder.clearContext()
    }

    private fun twoTabs(resource: String) {
        val storage = mock(StorageResolver::class.java)
        val pool = mock(HikariDataSource::class.java)
        val connection = mock(Connection::class.java)
        val statement = mock(PreparedStatement::class.java)
        val rows = mock(ResultSet::class.java)
        `when`(storage.getPlatformStorage()).thenReturn(pool)
        `when`(pool.connection).thenReturn(connection)
        `when`(connection.prepareStatement(anyString())).thenReturn(statement)
        `when`(statement.executeQuery()).thenReturn(rows)
        `when`(rows.next()).thenReturn(true)
        var persisted = "{\"title\":\"original\"}"
        `when`(rows.getString(1)).thenAnswer { persisted }
        val filter = ConcurrentEditFilter(storage, mock(StudyAuthorizationService::class.java))
        val path = "/chronicle/v3/${if (resource.startsWith("questionnaire")) "survey" else "study"}/${UUID.randomUUID()}/$resource"
        val head = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("HEAD", path), head, FilterChain { _, _ -> })
        val revision = head.getHeader("ETag")
        assertNotNull(revision)
        if (resource.startsWith("participant")) {
            val loaded = MockHttpServletResponse()
            filter.doFilter(MockHttpServletRequest("GET", path), loaded,
                FilterChain { _, _ -> fail("annotation snapshot must come from the locked row") })
            assertEquals(persisted, loaded.contentAsString)
            assertEquals(revision, loaded.getHeader("ETag"))
        }
        var writes = 0
        val first = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("PATCH", path).apply { addHeader("If-Match", revision!!) }, first,
            FilterChain { _, _ -> persisted = "{\"title\":\"first tab\"}"; writes++ })
        assertEquals(200, first.status)
        val stale = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("PATCH", path).apply { addHeader("If-Match", revision!!) }, stale,
            FilterChain { _, _ -> persisted = "second tab"; writes++ })
        assertEquals(412, stale.status)
        assertEquals(1, writes)
        assertEquals("{\"title\":\"first tab\"}", persisted)
        val missing = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("PATCH", path), missing, FilterChain { _, _ -> writes++ })
        assertEquals(428, missing.status)
        assertEquals(1, writes)
    }
}
