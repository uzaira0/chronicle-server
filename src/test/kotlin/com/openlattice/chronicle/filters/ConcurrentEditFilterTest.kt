package com.openlattice.chronicle.filters

import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.study.StudyApi
import com.openlattice.chronicle.survey.SurveyApi
import com.zaxxer.hikari.HikariDataSource
import jakarta.servlet.FilterChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

class ConcurrentEditFilterTest {
    @Test fun annotationTabsCannotOverwriteTheFirstSave() = twoTabs("participant/P-1/annotations")
    @Test fun questionnaireTabsCannotOverwriteTheFirstSave() = twoTabs("questionnaire/${UUID.randomUUID()}")

    @Test fun encodedParticipantSeparatorsCannotBypassTheRevisionGuard() = twoTabs("participant/P%2F1/annotations")

    @Test fun mappedStaticSegmentsCannotBypassTheRevisionGuard() {
        val family = StudyApi.CONTROLLER.substringAfterLast('/')
        for (spelling in listOf("%${family.first().code.toString(16)}${family.drop(1)}", "$family;source=dashboard")) {
            twoTabs("participant/P-1/annotations", pathTransform = { it.replace("/$family/", "/$spelling/") })
        }
    }

    @Test fun encodedIdentifiersCannotBypassTheRevisionGuard() {
        val study = UUID.randomUUID()
        for (studyPath in listOf(study.toString().replace("-", "%2d"), "%20$study%20")) {
            twoTabs("participant/P%2d1/annotations", studyPath)
            twoTabs("questionnaire/%20${UUID.randomUUID().toString().replace("-", "%2d")}%20", studyPath)
        }
    }

    @Test fun compressedEntityTagsMatchOnlyTheirOwnRevision() {
        for (resource in listOf("participant/P-1/annotations", "questionnaire/${UUID.randomUUID()}")) {
            for (suffix in listOf("--gzip", "-gzip", "--br", "-br", "--zstd", "-zstd", "--deflate")) {
                twoTabs(resource) { revision -> "W/\"${revision.trim('"')}$suffix\"" }
            }
        }
    }

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

    private fun twoTabs(
        resource: String,
        studyPath: String = UUID.randomUUID().toString(),
        pathTransform: (String) -> String = { it },
        ifMatch: (String) -> String = { it },
    ) {
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
        val base = if (resource.startsWith("questionnaire")) SurveyApi.BASE else StudyApi.BASE
        val path = pathTransform("$base/$studyPath/$resource")
        val head = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("HEAD", path), head, FilterChain { _, _ -> })
        val revision = requireNotNull(head.getHeader("ETag")) { "HEAD response should include the resource revision" }
        assertEquals(204, head.status)
        assertEquals("", head.contentAsString)
        clearInvocations(connection)
        val loaded = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("GET", path), loaded, FilterChain { _, handler ->
            assertEquals("the handler reads on the locked connection", connection, PinnedPlatformConnection.owningConnection(pool))
            handler.writer.write(persisted)
        })
        assertEquals(200, loaded.status)
        assertEquals(persisted, loaded.contentAsString)
        assertEquals(revision, loaded.getHeader("ETag"))
        verify(connection).commit()
        var writes = 0
        val first = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("PATCH", path).apply { addHeader("If-Match", ifMatch(revision)) }, first,
            FilterChain { _, _ -> persisted = "{\"title\":\"first tab\"}"; writes++ })
        assertEquals(200, first.status)
        val stale = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("PATCH", path).apply { addHeader("If-Match", ifMatch(revision)) }, stale,
            FilterChain { _, _ -> persisted = "second tab"; writes++ })
        assertEquals(412, stale.status)
        assertFilterError(stale, 412, "This resource changed; reload and merge before saving")
        assertEquals(ConcurrentEditFilter.revision(persisted), stale.getHeader("ETag"))
        assertEquals(1, writes)
        assertEquals("{\"title\":\"first tab\"}", persisted)
        val missing = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("PATCH", path), missing, FilterChain { _, _ -> writes++ })
        assertEquals(428, missing.status)
        assertFilterError(missing, 428, "Reload before saving; a resource revision is required")
        assertEquals(ConcurrentEditFilter.revision(persisted), missing.getHeader("ETag"))
        assertEquals(1, writes)
    }

    @Test fun successfulPatchOmitsTheOldRevisionAndRunsCommittedCallbacks() {
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
        val json = "{}"
        `when`(rows.getString(1)).thenReturn(json)
        var committed = false
        val response = MockHttpServletResponse()
        val request = MockHttpServletRequest("PATCH", "/chronicle/v3/study/${UUID.randomUUID()}/participant/p1/annotations")
        request.addHeader("If-Match", ConcurrentEditFilter.revision(json))
        ConcurrentEditFilter(storage, mock(StudyAuthorizationService::class.java)).doFilter(request, response,
            FilterChain { _, _ -> PinnedPlatformConnection.afterCommit(pool, "audit") { committed = true } })
        assertNull(response.getHeader("ETag"))
        assertTrue(committed)
        verify(connection).commit()

        doThrow(SQLException("injected commit failure")).`when`(connection).commit()
        committed = false
        val failed = MockHttpServletResponse()
        assertThrows(SQLException::class.java) {
            ConcurrentEditFilter(storage, mock(StudyAuthorizationService::class.java)).doFilter(request, failed,
                FilterChain { _, output ->
                    PinnedPlatformConnection.afterCommit(pool, "audit") { committed = true }
                    output.writer.write("saved"); output.flushBuffer()
                })
        }
        assertTrue("Uncommitted success must stay buffered", !failed.isCommitted)
        assertEquals("", failed.contentAsString)
        assertTrue("Failed commits must not dispatch success callbacks", !committed)
    }

}
