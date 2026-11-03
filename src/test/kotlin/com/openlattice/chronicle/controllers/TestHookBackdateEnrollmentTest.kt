package com.openlattice.chronicle.controllers

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.UsageEnrollmentFixtures.enrollUsageFixture
import com.openlattice.chronicle.UsageEnrollmentFixtures.prepareUsageFixture
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.util.tests.TestDataFactory
import com.openlattice.chronicle.configuration.ChronicleAuthConfiguration
import com.openlattice.chronicle.storage.StorageResolver
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.mock.env.MockEnvironment

/** The browser suite's test-only hook that gives a participant ended survey days (enrolled N days ago). */
class TestHookBackdateEnrollmentTest : ChronicleServerTests() {
    private val mapper = ObjectMappers.newJsonMapper()
    private val http = HttpClient.newHttpClient()

    private fun backdate(studyId: UUID, participantId: String, days: Int): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://localhost:$testHttpPort/chronicle/v3/admin/test-only/backdate-enrollment"))
            .header("Authorization", "Bearer ${activeFixtureToken(testUser1)}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                mapOf("studyId" to studyId, "participantId" to participantId, "days" to days),
            ))).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun keyCreatedAt(studyId: UUID, participantId: String): List<OffsetDateTime> = hds.connection.use { connection ->
        connection.prepareStatement(
            "SELECT created_at FROM api_keys WHERE study_id = ? AND participant_id = ? ORDER BY created_at",
        ).use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, participantId)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getObject(1, OffsetDateTime::class.java)) }
            }
        }
    }

    @Test
    fun backdatingMovesOnlyThatParticipantsEnrollmentKeys() {
        val studyId = clientUser1.studyApi.createStudy(Study(title = "Backdated enrollment", contact = "tests@openlattice.com"))
        prepareUsageFixture(clientUser1, studyId)
        val (target, other) = List(2) {
            TestDataFactory.participant(ParticipationStatus.ENROLLED).also { participant ->
                clientUser1.studyApi.registerParticipant(studyId, participant)
                enrollUsageFixture(studyId, participant.participantId, TestDataFactory.androidDevice())
            }.participantId
        }
        val before = keyCreatedAt(studyId, target)
        val otherBefore = keyCreatedAt(studyId, other)
        val otherStudy = clientUser1.studyApi.createStudy(Study(title = "Other enrollment", contact = "tests@openlattice.com"))
        prepareUsageFixture(clientUser1, otherStudy)
        clientUser1.studyApi.registerParticipant(otherStudy, TestDataFactory.participant(ParticipationStatus.ENROLLED).copy(participantId = target))
        enrollUsageFixture(otherStudy, target, TestDataFactory.androidDevice())
        val otherStudyBefore = keyCreatedAt(otherStudy, target)
        assertEquals(1, before.size)

        val response = backdate(studyId, target, 3)

        assertEquals(response.body(), 200, response.statusCode())
        assertEquals(1, mapper.readTree(response.body()).path("keys").asInt())
        assertEquals(before.map { it.minus(Duration.ofDays(3)).toInstant() }, keyCreatedAt(studyId, target).map { it.toInstant() })
        assertEquals("another participant's keys are untouched", otherBefore, keyCreatedAt(studyId, other))
        assertEquals("the same participant ID in another study is untouched", otherStudyBefore, keyCreatedAt(otherStudy, target))

        for (days in listOf(0, -1, 401)) assertEquals("days=$days", 400, backdate(studyId, target, days).statusCode())
        assertEquals("a participant with no enrollment", 404, backdate(studyId, "never-enrolled", 3).statusCode())
        assertEquals(before.map { it.minus(Duration.ofDays(3)).toInstant() }, keyCreatedAt(studyId, target).map { it.toInstant() })
    }

    @Test
    fun backdatingUsesElapsedHoursAcrossTheDatabaseSessionsDaylightSavingBoundary() {
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = hds.jdbcUrl
            username = hds.username
            password = hds.password
            maximumPoolSize = 1
            minimumIdle = 0
            connectionInitSql = "SET TIME ZONE 'America/Chicago'"
        }).use { pool ->
            val study = UUID.randomUUID()
            val before = OffsetDateTime.parse("2026-11-01T12:00:00Z")
            pool.connection.use { connection ->
                connection.createStatement().use { it.execute(
                    "CREATE TEMP TABLE api_keys (study_id uuid, participant_id text, created_at timestamptz)",
                ) }
                connection.prepareStatement("INSERT INTO api_keys VALUES (?, 'dst-participant', ?)").use {
                    it.setObject(1, study)
                    it.setObject(2, before)
                    assertEquals(1, it.executeUpdate())
                }
            }
            val storage = mock<StorageResolver>()
            whenever(storage.getPlatformStorage()).thenReturn(pool)
            val controller = TestHookController(mock(), ChronicleAuthConfiguration(testingLoginEnabled = true),
                MockEnvironment().apply { setActiveProfiles("test") }, storage)
            val response = controller.backdateEnrollment(BackdateEnrollmentRequest(study, "dst-participant", 3))
            assertEquals(200, response.statusCode.value())
            assertEquals(1, response.body?.get("keys"))
            assertEquals(before.minus(Duration.ofDays(3)).toInstant(), onlyKeyCreatedAt(pool))
        }
    }

    private fun onlyKeyCreatedAt(pool: HikariDataSource): java.time.Instant = pool.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT created_at FROM api_keys").use { rows ->
                org.junit.Assert.assertTrue(rows.next())
                rows.getObject(1, OffsetDateTime::class.java).toInstant().also { org.junit.Assert.assertFalse(rows.next()) }
            }
        }
    }
}
