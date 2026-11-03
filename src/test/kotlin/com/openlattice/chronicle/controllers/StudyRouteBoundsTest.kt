package com.openlattice.chronicle.controllers

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.apikey.ApiKeyCreateRequest
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.util.tests.TestDataFactory
import com.openlattice.chronicle.util.normalizeEntityTag
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real HTTP pages retain their public shapes and never load a whole study into one response. */
class StudyRouteBoundsTest : ChronicleServerTests() {
    @Test fun compliancePendingEnrollmentsAndIosStatusPageTheirSourceRows() {
        val study = clientUser1.studyApi.createStudy(Study(title = "sibling pages", contact = "pi@example.org"))
        listOf("page-a", "page-b", "page-c").forEach { participant ->
            clientUser1.studyApi.registerParticipant(study,
                TestDataFactory.participant(com.openlattice.chronicle.data.ParticipationStatus.ENROLLED).copy(participantId = participant))
            hds.connection.use { connection ->
                connection.prepareStatement("""INSERT INTO ios_screen_time_usage
                    (study_id, participant_id, random_device_id, record_id, row_kind, captured_at,
                     observation_start, observation_end, timezone, app_name, duration_seconds)
                    VALUES (?, ?, gen_random_uuid(), gen_random_uuid(), 'application', now(),
                        now() - interval '1 hour', now(), 'UTC', 'Fixture', 60)""").use {
                    it.setObject(1, study); it.setString(2, participant); assertEquals(1, it.executeUpdate())
                }
                connection.prepareStatement("""INSERT INTO participant_form_access_codes
                    (access_code_id, token_hash, study_id, participant_id, form_kind, issuer_type, issued_by,
                     expires_at, exchanged_at, enrollment_attempt_id, enrollment_source_device_hash,
                     enrollment_random_device_id, enrollment_manifest_digest, enrollment_request_hash,
                     enrollment_proposed_key_hash, enrollment_replay_expires_at)
                    VALUES (gen_random_uuid(), decode(md5(gen_random_uuid()::text), 'hex'), ?, ?, 'ENROLLMENT',
                        'RESEARCHER', 'fixture', now() + interval '1 day', now(), gen_random_uuid(), repeat('a', 64),
                        gen_random_uuid(), repeat('b', 64), repeat('c', 64), repeat('d', 64), now() + interval '1 day')""").use {
                    it.setObject(1, study); it.setString(2, participant); assertEquals(1, it.executeUpdate())
                }
            }
        }
        assertEquals(listOf("page-b"), read("/chronicle/v3/study/$study/participants/ios/upload-status?limit=1&offset=1")
            .fieldNames().asSequence().toList())
        assertEquals(listOf("page-b"), read("/chronicle/v3/study/$study/pending-enrollments?limit=1&offset=1")
            .map { it.asText() })
        val request = Request.Builder()
            .url("http://localhost:$testHttpPort/chronicle/v3/compliance/study/$study?limit=1&offset=1")
            .header("Authorization", "Bearer ${activeFixtureToken(testUser1)}").build()
        OkHttpClient().newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("2", response.header("X-Next-Offset"))
            val page = ObjectMappers.getJsonMapper().readTree(checkNotNull(response.body).string())
            assertEquals(listOf("page-b"), page[study.toString()].fieldNames().asSequence().toList())
        }
    }
    @Test fun filteredReplacementRequiresTheRevisionOfTheActualReadAndDeltaWritesAdvanceIt() {
        val study = clientUser1.studyApi.createStudy(Study(title = "filter revision", contact = "pi@example.org"))
        hds.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO filtered_apps (study_id, app_package_name) VALUES (?, 'org.original') ON CONFLICT DO NOTHING",
            ).use {
                it.setObject(1, study); it.executeUpdate()
            }
        }
        val path = "/chronicle/v3/survey/$study/filtered"
        val client = OkHttpClient()
        val revision = client.newCall(filteredRequest(path, "GET")).execute().use {
            assertEquals(200, it.code)
            checkNotNull(it.header("ETag")) { "Filtered-app reads must publish their revision" }
        }
        client.newCall(filteredRequest(path, "PUT")).execute().use { assertEquals(428, it.code) }
        val changed = client.newCall(filteredRequest(path, "PUT", revision)).execute().use {
            assertEquals(200, it.code); checkNotNull(it.header("ETag"))
        }
        assertEquals(normalizeEntityTag(changed), cachedStudyRevision(study.toString()))
        client.newCall(filteredRequest(path, "PUT", revision)).execute().use { assertEquals(412, it.code) }
        client.newCall(filteredRequest(path, "PATCH")).execute().use { assertEquals(200, it.code) }
        client.newCall(filteredRequest(path, "PUT", changed)).execute().use { assertEquals(412, it.code) }
        val latest = client.newCall(filteredRequest(path, "GET")).execute().use { checkNotNull(it.header("ETag")) }
        assertEquals(normalizeEntityTag(latest), cachedStudyRevision(study.toString()))
        client.newCall(filteredRequest(path, "DELETE")).execute().use { assertEquals(200, it.code) }
        assertEquals(normalizeEntityTag(latest).toLong() + 1, cachedStudyRevision(study.toString()).toLong())
    }

    private fun cachedStudyRevision(studyId: String): String = OkHttpClient().newCall(
        filteredRequest("/chronicle/v3/study/$studyId", "GET"),
    ).execute().use { response ->
        assertEquals(200, response.code)
        normalizeEntityTag(checkNotNull(response.header("ETag")))
    }

    private fun filteredRequest(path: String, method: String, revision: String? = null): Request = Request.Builder()
        .url("http://localhost:$testHttpPort$path")
        .header("Authorization", "Bearer ${activeFixtureToken(testUser1)}")
        .apply {
            if (revision != null) header("If-Match", revision)
            if (method != "GET") method(method, "[\"org.updated\"]".toRequestBody("application/json".toMediaType()))
        }.build()
    @Test fun devicePagesBoundRowsEvenWhenOneParticipantHasMultipleDevices() {
        val study = clientUser1.studyApi.createStudy(Study(title = "device pages", contact = "pi@example.org"))
        listOf("page-a", "page-b", "page-c").forEach { participant ->
            clientUser1.studyApi.registerParticipant(study, TestDataFactory.participant().copy(participantId = participant))
            hds.connection.use { connection ->
                connection.prepareStatement("""INSERT INTO devices
                    (study_id, participant_id, random_device_id, device_type, source_device)
                    SELECT ?, ?, gen_random_uuid(), 'Android', '{}'::jsonb FROM generate_series(1, 2)""").use {
                    it.setObject(1, study); it.setString(2, participant); assertEquals(2, it.executeUpdate())
                }
            }
        }
        val page = read("/chronicle/v3/study/$study/devices?limit=1&offset=1")
        assertEquals(listOf("page-a"), page.fieldNames().asSequence().toList())
        assertEquals(1, page["page-a"].size())
        assertEquals(listOf("page-b"), read("/chronicle/v3/study/$study/devices?limit=1&offset=2").fieldNames().asSequence().toList())
        assertEquals(0, read("/chronicle/v3/study/$study/devices?limit=1&offset=6").size())
    }

    @Test fun apiKeysUseAStableBoundedPage() {
        val study = clientUser1.studyApi.createStudy(Study(title = "key pages", contact = "pi@example.org"))
        val api = com.openlattice.chronicle.client.RetrofitClientFactory.newClient(
            com.openlattice.chronicle.client.Environment.TESTING_CHRONICLE,
        ) { activeFixtureToken(testUser1) }.create(com.openlattice.chronicle.apikey.ApiKeyApi::class.java)
        repeat(3) { api.createApiKey(study, ApiKeyCreateRequest(name = "page-$it")) }
        val all = read("/chronicle/v3/study/$study/api-keys?limit=500&offset=0")
        val second = read("/chronicle/v3/study/$study/api-keys?limit=1&offset=1")
        assertEquals(3, all.size())
        assertEquals(1, second.size())
        assertEquals(all[1], second[0])
    }

    private fun read(path: String): com.fasterxml.jackson.databind.JsonNode {
        val request = Request.Builder().url("http://localhost:$testHttpPort$path")
            .header("Authorization", "Bearer ${activeFixtureToken(testUser1)}").build()
        OkHttpClient().newCall(request).execute().use { response ->
            assertEquals(path, 200, response.code)
            val body = ObjectMappers.getJsonMapper().readTree(checkNotNull(response.body).string())
            assertTrue(body.isContainerNode)
            return body
        }
    }
}
