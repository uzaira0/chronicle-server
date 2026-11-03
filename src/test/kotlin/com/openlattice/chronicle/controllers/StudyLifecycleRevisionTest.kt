package com.openlattice.chronicle.controllers

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.survey.SurveySettings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Archive and restore apply only to the study revision the dashboard saw, like every other study write, and the
 * revision a settings read returns is the one of the map it returns.
 */
class StudyLifecycleRevisionTest : ChronicleServerTests() {
    private val http = HttpClient.newHttpClient()

    private fun send(path: String, method: String = "GET", ifMatch: String? = null, body: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$testHttpPort/chronicle/v3/study$path"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer ${activeFixtureToken(testUser1)}")
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        body?.let { builder.header("Content-Type", "application/json") }
        ifMatch?.let { builder.header("If-Match", it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun observed(studyId: UUID): String = checkNotNull(send("/$studyId/settings").headers().firstValue("ETag").orElse(null))

    private fun status(studyId: UUID): String = send("/$studyId/lifecycle").body().trim('"')

    @Test
    fun staleArchiveRetryCannotUndoALaterRestore() {
        val studyId = clientUser1.studyApi.createStudy(Study(title = "Lifecycle revisions", contact = "tests@openlattice.com"))
        assertEquals(428, send("/$studyId/archive", "POST").statusCode())
        assertEquals("ACTIVE", status(studyId))

        // Tab A archives; the server commits but A never sees the response.
        val seenByA = observed(studyId)
        assertEquals(200, send("/$studyId/archive", "POST", seenByA).statusCode())
        assertNotEquals(seenByA, observed(studyId))
        // Tab B refreshes and restores.
        val seenByB = observed(studyId)
        assertEquals(200, send("/$studyId/unarchive", "POST", seenByB).statusCode())

        val staleRetry = send("/$studyId/archive", "POST", seenByA)
        assertEquals(staleRetry.body(), 412, staleRetry.statusCode())
        assertEquals("ACTIVE", status(studyId))

        // An identical replay of the restore that already landed is idempotent and moves no revision.
        val current = observed(studyId)
        assertEquals(200, send("/$studyId/unarchive", "POST", seenByB).statusCode())
        assertEquals(current, observed(studyId))
        assertEquals(428, send("/$studyId/unarchive", "POST").statusCode())
        assertEquals("ACTIVE", status(studyId))
    }

    @Test
    fun aSettingsReadUnderConcurrentWritesReturnsTheRevisionOfItsOwnMap() {
        val studyId = clientUser1.studyApi.createStudy(Study(title = "Coherent settings", contact = "tests@openlattice.com"))
        val path = "/$studyId/settings/type/Survey"
        val base = revision(send(path))
        // Write n lands at revision base + n carrying threshold n, so every read can name the map its ETag belongs to.
        val writes = 200
        val writer = Executors.newSingleThreadExecutor()
        try {
            val written = writer.submit {
                for (n in 1..writes) {
                    val body = mapper.writeValueAsString(SurveySettings(appUsageThresholdInSeconds = n))
                    val saved = send(path, "PATCH", "\"${base + n - 1}\"", body)
                    assertEquals(saved.body(), 200, saved.statusCode())
                }
            }
            var reads = 0
            val deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos()
            while (!written.isDone && System.nanoTime() < deadline) {
                val read = send(path)
                val n = revision(read) - base
                val expected = if (n == 0L) SurveySettings().appUsageThresholdInSeconds else n.toInt()
                assertEquals("read at revision ${revision(read)}", expected, threshold(read))
                reads++
            }
            written.get(30, TimeUnit.SECONDS)
            assertTrue("reads overlapped the writes", reads > 0)
        } finally {
            writer.shutdownNow()
        }
        val last = send(path)
        assertEquals(base + writes, revision(last))
        assertEquals(writes, threshold(last))
    }

    /** An edge-compressed ETag of the current revision saves; the same spelling of a superseded one is refused. */
    @Test
    fun compressedSettingsRevisionsSaveOnlyWhileCurrent() {
        val studyId = clientUser1.studyApi.createStudy(Study(title = "Compressed revisions", contact = "tests@openlattice.com"))
        val path = "/$studyId/settings/type/Survey"
        val seen = revision(send(path))
        val saved = send(path, "PATCH", "W/\"$seen--gzip\"", mapper.writeValueAsString(SurveySettings(appUsageThresholdInSeconds = 77)))
        assertEquals(saved.body(), 200, saved.statusCode())
        val stale = send(path, "PATCH", "\"$seen-gzip\"", mapper.writeValueAsString(SurveySettings(appUsageThresholdInSeconds = 5)))
        assertEquals(stale.body(), 412, stale.statusCode())
        val current = send(path)
        assertEquals(seen + 1, revision(current))
        assertEquals(77, threshold(current))
    }

    private fun revision(response: HttpResponse<String>) = response.headers().firstValue("ETag").orElseThrow().trim('"').toLong()

    private fun threshold(response: HttpResponse<String>) = mapper.readTree(response.body()).get("appUsageThresholdInSeconds").asInt()

    private companion object {
        private val mapper = ObjectMappers.newJsonMapper()
    }
}
