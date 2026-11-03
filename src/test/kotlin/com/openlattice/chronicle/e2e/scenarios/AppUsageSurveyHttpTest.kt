package com.openlattice.chronicle.e2e.scenarios

import com.fasterxml.jackson.databind.JsonNode
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.awaitSessionsBlockedBy
import com.openlattice.chronicle.ChronicleServerTests
import com.openlattice.chronicle.UsageEnrollmentFixtures
import com.openlattice.chronicle.client.updateParticipationStatusAsRead
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.filters.ParticipantFormAccessFilter
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.participantaccess.ParticipantAccessCodeIssuerType
import com.openlattice.chronicle.services.participantaccess.ParticipantFormAccessService
import com.openlattice.chronicle.storage.rls.RLSRequestContext
import com.openlattice.chronicle.study.Study
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.timeusediary.TimeUseDiarySettings
import com.openlattice.chronicle.survey.AppUsageDayConflict
import com.openlattice.chronicle.survey.SurveyApi
import com.openlattice.chronicle.util.tests.TestDataFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The daily app-usage survey through the real participant form session, HTTP routes, receipt service and database:
 * answers and their receipt commit together, simultaneous tabs and late uploads keep committed answers, and every
 * documented rejection answers its status and writes nothing.
 */
class AppUsageSurveyHttpTest : ChronicleServerTests() {
    private val http = HttpClient.newHttpClient()
    private val mapper = ObjectMappers.getJsonMapper()
    private val forms = testServer.context.getBean(ParticipantFormAccessService::class.java)
    private val studyZone = ZoneId.of("America/Santiago")
    private val schemas: JsonNode = checkNotNull(javaClass.getResourceAsStream("/openapi-schemas.json")).use {
        mapper.readTree(it).path("schemas")
    }

    private data class Session(val token: String, val csrf: String)
    private data class StoredAnswer(val app: String, val users: List<String>, val submittedAt: OffsetDateTime, val surveyDate: LocalDate)

    /** A shared-device participant enrolled through the public protocol four days ago. */
    private inner class Subject {
        val study: UUID = clientUser1.studyApi.createStudy(Study(title = "app usage survey http", contact = "test@example.org"))
        val id: String = TestDataFactory.participant().also { participant ->
            UsageEnrollmentFixtures.prepareUsageFixture(clientUser1, study)
            clientUser1.studyApi.updateStudySettings(study, StudySettingType.TimeUseDiary, TimeUseDiarySettings(timeZone = studyZone.id))
            clientUser1.studyApi.registerParticipant(study, participant)
            UsageEnrollmentFixtures.enrollUsageFixture(study, participant.participantId, TestDataFactory.androidDevice())
        }.participantId
        private val base = "http://localhost:$testHttpPort${SurveyApi.BASE}/$study${SurveyApi.PARTICIPANT_PATH}/$id"

        init {
            hds.connection.use { connection ->
                connection.prepareStatement(
                    "UPDATE api_keys SET created_at = now() - interval '4 days' WHERE study_id = ? AND participant_id = ?",
                ).use { statement ->
                    statement.setObject(1, study)
                    statement.setString(2, id)
                    assertEquals(1, statement.executeUpdate())
                }
            }
        }

        val days: JsonNode get() = read(open(), SurveyApi.APP_USAGE_DAYS_PATH)
        val zone: ZoneId get() = studyZone
        val today: LocalDate = hds.connection.use(UsageEnrollmentFixtures::databaseNow).atZoneSameInstant(studyZone).toLocalDate()

        fun open(): Session {
            val code = RLSRequestContext.withSystemContext {
                forms.createAccessCode(study, id, ParticipantFormKind.APP_USAGE, null, null, null,
                    ParticipantAccessCodeIssuerType.RESEARCHER, "survey-http")
            }
            val exchanged = checkNotNull(forms.exchangeAccessCode(code.accessCode))
            return Session(exchanged.rawSessionToken, exchanged.response.csrfToken)
        }

        fun get(session: Session, path: String, query: String = ""): HttpResponse<String> = http.send(
            HttpRequest.newBuilder(URI("$base$path$query"))
                .header("Cookie", "${ParticipantFormAccessFilter.SESSION_COOKIE}=${session.token}").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        fun read(session: Session, path: String, query: String = ""): JsonNode {
            val response = get(session, path, query)
            assertEquals(response.body(), 200, response.statusCode())
            return mapper.readTree(response.body())
        }

        fun day(session: Session, date: LocalDate): JsonNode = read(session, SurveyApi.APP_USAGE_PATH, "?date=$date")

        fun request(session: Session, date: String?, body: String, key: UUID): HttpRequest = HttpRequest.newBuilder(
            URI("$base${SurveyApi.APP_USAGE_PATH}" + (date?.let { "?date=${URLEncoder.encode(it, StandardCharsets.UTF_8)}" } ?: "")),
        )
            .header("Cookie", "${ParticipantFormAccessFilter.SESSION_COOKIE}=${session.token}")
            .header(ParticipantFormAccessFilter.CSRF_HEADER, session.csrf)
            .header(ParticipantFormAccessFilter.IDEMPOTENCY_HEADER, key.toString())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()

        fun submit(session: Session, date: Any?, body: String, key: UUID = UUID.randomUUID()): HttpResponse<String> =
            http.send(request(session, date?.toString(), body, key), HttpResponse.BodyHandlers.ofString())

        /** [apps] used for five minutes each on [date], from ten o'clock in the study zone, an hour apart. */
        fun used(date: LocalDate, vararg apps: String) = apps.forEachIndexed { index, app ->
            val from = date.atTime(10 + index, 0).atZone(zone).toOffsetDateTime()
            hds.connection.use { connection ->
                connection.prepareStatement("""
                    INSERT INTO chronicle_usage_events (study_id, participant_id, app_package_name, application_label,
                        interaction_type, event_type, event_timestamp, timezone, username, uploaded_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, '', now())
                """.trimIndent()).use { statement ->
                    for ((type, at) in listOf(1 to from, 2 to from.plusMinutes(5))) {
                        statement.setString(1, study.toString())
                        statement.setString(2, id)
                        statement.setString(3, app)
                        statement.setString(4, app.substringAfterLast('.'))
                        statement.setString(5, if (type == 1) "Move to Foreground" else "Move to Background")
                        statement.setInt(6, type)
                        statement.setObject(7, at)
                        statement.setString(8, zone.id)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
        }

        /** The answer body the survey page sends: each listed block start exactly as the server wrote it. */
        fun answers(day: JsonNode, users: Map<String, List<String>>): String = mapper.writeValueAsString(
            day.path("apps").filter { it.path("appPackageName").asText() in users }
                .sortedBy { it.path("appPackageName").asText() }.map { app ->
                mapOf("appPackageName" to app.path("appPackageName").asText(),
                    "blockStart" to app.path("blocks").single().path("start").asText(),
                    "users" to users.getValue(app.path("appPackageName").asText()))
            },
        )

        fun stored(): Map<String, StoredAnswer> = hds.connection.use { connection ->
            connection.prepareStatement("""
                SELECT app_package_name, users, submission_date, survey_date FROM app_usage_survey
                WHERE study_id = ? AND participant_id = ?
            """.trimIndent()).use { statement ->
                statement.setObject(1, study)
                statement.setString(2, id)
                statement.executeQuery().use { rows ->
                    buildMap {
                        while (rows.next()) {
                            val app = rows.getString(1)
                            put("$app@${rows.getObject(4, LocalDate::class.java)}", StoredAnswer(app,
                                (rows.getArray(2).array as Array<*>).map { it.toString() },
                                rows.getObject(3, OffsetDateTime::class.java), rows.getObject(4, LocalDate::class.java)))
                        }
                    }
                }
            }
        }

        fun receipts(): List<String> = hds.connection.use { connection ->
            connection.prepareStatement("""
                SELECT status FROM participant_form_submission_receipts
                WHERE study_id = ? AND participant_id = ? AND form_kind = ? ORDER BY status
            """.trimIndent()).use { statement ->
                statement.setObject(1, study)
                statement.setString(2, id)
                statement.setString(3, ParticipantFormKind.APP_USAGE.name)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }
    }

    private fun conflict(response: HttpResponse<String>): String {
        assertEquals(response.body(), 409, response.statusCode())
        return mapper.readTree(response.body()).path("code").asText()
    }

    /** Makes the maps answer row of [study] fail as [mode] (see INSTALL_FAULT); null clears the fault. */
    private fun fault(study: UUID, mode: String?) = hds.connection.use { connection ->
        connection.prepareStatement("DELETE FROM fixture_app_usage_fault WHERE study_id = ?").use {
            it.setObject(1, study); it.executeUpdate()
        }
        if (mode != null) connection.prepareStatement("INSERT INTO fixture_app_usage_fault VALUES (?, ?, ?)").use {
            it.setObject(1, study); it.setString(2, "org.example.maps"); it.setString(3, mode); it.executeUpdate()
        }
    }

    /** Each listed app's users for its single block; null while unanswered. */
    private fun usersByApp(day: JsonNode): Map<String, List<String>?> = day.path("apps").associate { app ->
        val users = app.path("blocks").single().path("users")
        app.path("appPackageName").asText() to users.takeUnless { it.isNull || it.isMissingNode }?.map { it.asText() }
    }

    /** Runs [requests] together while the answer table refuses inserts, then lets them go at once. */
    private fun heldTogether(requests: List<HttpRequest>): List<HttpResponse<String>> = hds.connection.use { holder ->
        holder.autoCommit = false
        holder.createStatement().use { it.execute("LOCK TABLE app_usage_survey IN SHARE ROW EXCLUSIVE MODE") }
        val pending = requests.map { http.sendAsync(it, HttpResponse.BodyHandlers.ofString()) }
        try {
            awaitSessionsBlockedBy(holder, requests.size)
        } finally {
            holder.commit()
        }
        CompletableFuture.allOf(*pending.toTypedArray()).get(60, TimeUnit.SECONDS)
        pending.map { it.get(60, TimeUnit.SECONDS) }
    }

    /** Checks [node] against the OpenAPI schema [name]: required, documented, typed, and with known enum values. */
    private fun conformance(node: JsonNode, name: String): List<String> = conformance(node, schemas.path(name), name)

    private fun conformance(node: JsonNode, schema: JsonNode, path: String): List<String> {
        schema.path("\$ref").takeIf { it.isTextual }?.let { return conformance(node, schemas.path(it.asText().substringAfterLast('/')), path) }
        if (node.isNull) return if (schema.path("nullable").asBoolean(false)) emptyList() else listOf("$path is null")
        return when (schema.path("type").asText()) {
            "object" -> {
                val properties = schema.path("properties")
                schema.path("required").map { it.asText() }.filterNot(node::has).map { "$path.$it is missing" } +
                    node.fieldNames().asSequence().filterNot(properties::has).map { "$path.$it is undocumented" } +
                    properties.fields().asSequence().filter { node.has(it.key) }
                        .flatMap { conformance(node.get(it.key), it.value, "$path.${it.key}") }
            }
            "array" -> if (!node.isArray) listOf("$path is not an array")
            else node.flatMapIndexed { index, item -> conformance(item, schema.path("items"), "$path[$index]") }
            "integer" -> if (node.isIntegralNumber) emptyList() else listOf("$path is not an integer")
            "string" -> {
                val text = node.takeIf { it.isTextual }?.asText() ?: return listOf("$path is not a string")
                val enum = schema.path("enum").map { it.asText() }
                val parsed = runCatching {
                    when (schema.path("format").asText()) {
                        "date" -> LocalDate.parse(text)
                        "date-time" -> OffsetDateTime.parse(text)
                        "uuid" -> UUID.fromString(text)
                        else -> text
                    }
                }
                listOfNotNull(
                    "$path is not a ${schema.path("format").asText()}".takeIf { parsed.isFailure },
                    "$path=$text is not one of $enum".takeIf { enum.isNotEmpty() && text !in enum },
                )
            }
            else -> emptyList()
        }
    }

    /**
     * A database failure while the answers are written (out of disk at the second answer, or the writing
     * connection killed) leaves neither answers nor a receipt; the same attempt replayed then stores both answers and one
     * completed receipt; a replay after a lost response changes nothing; a new attempt for the same answers is a conflict.
     */
    @Test
    fun appUsageReceiptAndAnswersCommitTogether() {
        val subject = Subject()
        val date = subject.today.minusDays(1)
        subject.used(date, "org.example.maps", "org.example.chat")
        val session = subject.open()
        val body = subject.answers(subject.day(session, date),
            mapOf("org.example.maps" to listOf("Child"), "org.example.chat" to listOf("Parent", "Child")))
        val key = UUID.randomUUID()
        hds.connection.use { connection -> connection.createStatement().use { it.execute(INSTALL_FAULT) } }
        try {
            for (mode in listOf("disk_full", "terminate")) {
                fault(subject.study, mode)
                val failed = subject.submit(session, date, body, key)
                assertEquals("$mode: ${failed.body()}", 503, failed.statusCode())
                assertTrue(mode, failed.headers().firstValue("Retry-After").isPresent)
                assertEquals(mode, emptyMap<String, StoredAnswer>(), subject.stored())
                assertEquals(mode, emptyList<String>(), subject.receipts())
                fault(subject.study, null)
            }
        } finally {
            hds.connection.use { connection -> connection.createStatement().use { it.execute(REMOVE_FAULT) } }
        }

        val committed = subject.submit(session, date, body, key)
        assertEquals(committed.body(), 200, committed.statusCode())
        val answers = subject.stored()
        assertEquals(mapOf("org.example.maps" to listOf("Child"), "org.example.chat" to listOf("Parent", "Child")),
            answers.values.associate { it.app to it.users })
        assertEquals(setOf(date), answers.values.map { it.surveyDate }.toSet())
        assertEquals(listOf("COMPLETED"), subject.receipts())

        // The response was lost: the page sends the same attempt again.
        val replayed = subject.submit(session, date, body, key)
        assertEquals(replayed.body(), 200, replayed.statusCode())
        assertEquals(answers, subject.stored())
        assertEquals(listOf("COMPLETED"), subject.receipts())
        assertEquals(AppUsageDayConflict.ALREADY_ANSWERED.name, conflict(subject.submit(session, date, body)))
        assertEquals(answers, subject.stored())
        assertEquals(listOf("COMPLETED"), subject.receipts())
    }

    /**
     * Two tabs of one participant submit at the same moment. The same attempt from both stores one answer
     * set and both see success; different answers for one day store the winner's and tell the loser it is already
     * answered. An app uploaded after the page listed the day does not refuse the listed answers; it reopens the day,
     * and answering it closes the day without touching the earlier answers.
     */
    @Test
    fun concurrentAppUsageAttemptsPreserveCommittedAnswers() {
        val subject = Subject()
        val (first, second, third) = (1L..3L).map { subject.today.minusDays(it) }
        subject.used(first, "org.example.maps", "org.example.chat")
        subject.used(second, "org.example.video")
        subject.used(third, "org.example.maps", "org.example.chat")
        val tabA = subject.open()
        val tabB = subject.open()

        val same = subject.answers(subject.day(tabA, first), mapOf("org.example.maps" to listOf("Child"), "org.example.chat" to listOf("Child")))
        val key = UUID.randomUUID()
        val both = heldTogether(listOf(subject.request(tabA, first.toString(), same, key), subject.request(tabB, first.toString(), same, key)))
        assertEquals(both.map { it.body() }.toString(), listOf(200, 200), both.map { it.statusCode() })
        assertEquals(mapOf("org.example.maps" to listOf("Child"), "org.example.chat" to listOf("Child")),
            subject.stored().values.associate { it.app to it.users })
        assertEquals(listOf("COMPLETED"), subject.receipts())

        val listed = subject.day(tabA, second)
        val choices = listOf(listOf("Child"), listOf("Parent"))
        val raced = heldTogether(choices.mapIndexed { index, users ->
            subject.request(if (index == 0) tabA else tabB, second.toString(),
                subject.answers(listed, mapOf("org.example.video" to users)), UUID.randomUUID())
        })
        assertEquals(raced.map { it.body() }.toString(), listOf(200, 409), raced.map { it.statusCode() }.sorted())
        val loser = raced.single { it.statusCode() == 409 }
        assertEquals(AppUsageDayConflict.ALREADY_ANSWERED.name, conflict(loser))
        val winner = choices[raced.indexOfFirst { it.statusCode() == 200 }]
        assertEquals(winner, subject.stored().getValue("org.example.video@$second").users)
        assertEquals(listOf("COMPLETED", "COMPLETED"), subject.receipts())

        val page = subject.day(tabA, third)
        subject.used(third, "org.example.late")
        val listedAnswers = subject.answers(page, mapOf("org.example.maps" to listOf("Child"), "org.example.chat" to listOf("Sibling")))
        assertEquals(200, subject.submit(tabB, third, listedAnswers).statusCode())
        fun state() = subject.days.path("days").single { it.path("date").asText() == third.toString() }.path("state").asText()
        assertEquals("NEW_APPS", state())
        val reopened = subject.day(tabA, third)
        assertEquals(mapOf("org.example.maps" to listOf("Child"), "org.example.chat" to listOf("Sibling"),
            "org.example.late" to null), usersByApp(reopened))
        val late = subject.answers(reopened, mapOf("org.example.late" to listOf("Child")))
        assertEquals(200, subject.submit(tabA, third, late).statusCode())
        assertEquals("ANSWERED", state())
        val stored = subject.stored()
        assertEquals(listOf("Child"), stored.getValue("org.example.maps@$third").users)
        assertEquals(listOf("Sibling"), stored.getValue("org.example.chat@$third").users)
    }

    /**
     * The survey routes over the wire. Successful reads match their OpenAPI schemas and their block starts
     * are accepted back as answers; every documented rejection (no or invalid date, a day not ended, a missing or invalid
     * block start, an unlisted app or block, a duplicate answer, more than 10,000 answers) answers its status and code
     * and writes neither answers nor receipts. A pause keeps ended days answerable; Mark not enrolled stops the survey.
     */
    @Test
    fun surveyWirePayloadsAndRejectionsMatchTheContract() {
        val subject = Subject()
        val date = subject.today.minusDays(1)
        subject.used(date, "org.example.maps", "org.example.chat")
        val session = subject.open()
        val days = subject.read(session, SurveyApi.APP_USAGE_DAYS_PATH)
        assertEquals(emptyList<String>(), conformance(days, "AppUsageSurveyDays"))
        assertEquals(studyZone.id, days.path("timeZone").asText())
        val afterRead = hds.connection.use(UsageEnrollmentFixtures::databaseNow).atZoneSameInstant(studyZone).toLocalDate()
        assertTrue("today follows the database clock in Santiago", LocalDate.parse(days.path("today").asText()) in subject.today..afterRead)
        val day = subject.day(session, date)
        assertEquals(emptyList<String>(), conformance(day, "AppUsageDaySurvey"))
        val start = day.path("apps").first().path("blocks").single().path("start").asText()
        assertEquals(date.atStartOfDay(subject.zone).toInstant(), OffsetDateTime.parse(start).toInstant())

        fun answer(app: String, blockStart: Any?, users: List<String> = listOf("Child")) =
            mapOf("appPackageName" to app, "blockStart" to blockStart, "users" to users)
        fun body(vararg answers: Map<String, Any?>) = mapper.writeValueAsString(answers.toList())
        val maps = answer("org.example.maps", start)
        val rejections = listOf(
            Triple(null, body(maps), 400),
            Triple("2026-02-30", body(maps), 400),
            Triple("yesterday", body(maps), 400),
            Triple(subject.today.plusDays(1).toString(), body(maps), AppUsageDayConflict.DAY_UNAVAILABLE.name),
            Triple(date.toString(), body(answer("org.example.maps", null)), 400),
            Triple(date.toString(), body(answer("org.example.maps", "not a time")), 400),
            Triple(date.toString(), body(answer("org.example.unlisted", start)), AppUsageDayConflict.APPS_CHANGED.name),
            Triple(date.toString(), body(answer("org.example.maps", OffsetDateTime.parse(start).plusHours(1).toString())),
                AppUsageDayConflict.APPS_CHANGED.name),
            Triple(date.toString(), body(maps, maps), 400),
            Triple(date.toString(), mapper.writeValueAsString((0..10_000).map { answer("o.$it", start, emptyList()) }), 400),
            // Exactly 10,000 answers pass the size bound and reach the day rule.
            Triple(date.toString(), mapper.writeValueAsString((0 until 10_000).map { answer("o.$it", start, emptyList()) }),
                AppUsageDayConflict.APPS_CHANGED.name),
        )
        for ((query, payload, expected) in rejections) {
            val response = subject.submit(session, query, payload)
            val label = "date=$query body=${payload.take(120)}"
            if (expected is Int) assertEquals("$label: ${response.body()}", expected, response.statusCode())
            else assertEquals(label, expected, conflict(response))
            assertEquals(label, emptyList<String>(), conformance(mapper.readTree(response.body()), "ApiError"))
            assertEquals(label, emptyMap<String, StoredAnswer>(), subject.stored())
            assertEquals(label, emptyList<String>(), subject.receipts())
        }

        val accepted = subject.submit(session, date, subject.answers(day, mapOf("org.example.maps" to listOf("Child"),
            "org.example.chat" to emptyList())))
        assertEquals(accepted.body(), 200, accepted.statusCode())
        val answered = subject.day(session, date)
        assertEquals(emptyList<String>(), conformance(answered, "AppUsageDaySurvey"))
        assertEquals("ANSWERED", answered.path("state").asText())
        assertEquals(mapOf("org.example.maps" to listOf("Child"), "org.example.chat" to emptyList()), usersByApp(answered))

        // A pause stops collection, not the survey of days that already ended.
        val earlier = subject.today.minusDays(2)
        subject.used(earlier, "org.example.video")
        clientUser1.studyApi.updateParticipationStatusAsRead(subject.study, subject.id, ParticipationStatus.PAUSED)
        val paused = subject.submit(session, earlier, subject.answers(subject.day(session, earlier), mapOf("org.example.video" to listOf("Child"))))
        assertEquals(paused.body(), 200, paused.statusCode())
        assertEquals(listOf("Child"), subject.stored().getValue("org.example.video@$earlier").users)

        val beforeStop = subject.stored()
        // Read before the stop: a stopped participant can no longer open a session to read today.
        val ended = subject.today.minusDays(3)
        clientUser1.studyApi.updateParticipationStatusAsRead(subject.study, subject.id, ParticipationStatus.NOT_ENROLLED)
        val stopped = subject.submit(session, ended, "[]")
        assertTrue(stopped.body(), stopped.statusCode() in 400..499)
        assertEquals(beforeStop, subject.stored())
    }

    private companion object {
        /** Refuses one app's answer row for one study: SQLSTATE 53100 (disk full), or the writing connection killed. */
        private val INSTALL_FAULT = """
            CREATE TABLE fixture_app_usage_fault (study_id uuid PRIMARY KEY, app text NOT NULL, mode text NOT NULL);
            CREATE FUNCTION fixture_app_usage_fault() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS ${'$'}${'$'}
            DECLARE fault text;
            BEGIN
              SELECT mode INTO fault FROM fixture_app_usage_fault WHERE study_id = NEW.study_id AND app = NEW.app_package_name;
              IF fault = 'disk_full' THEN RAISE EXCEPTION 'fixture: no space left' USING ERRCODE = '53100'; END IF;
              IF fault = 'terminate' THEN PERFORM pg_terminate_backend(pg_backend_pid()); END IF;
              RETURN NEW;
            END;
            ${'$'}${'$'};
            CREATE TRIGGER fixture_app_usage_fault BEFORE INSERT ON app_usage_survey
            FOR EACH ROW EXECUTE FUNCTION fixture_app_usage_fault();
        """.trimIndent()

        private const val REMOVE_FAULT = "DROP TRIGGER fixture_app_usage_fault ON app_usage_survey; " +
            "DROP FUNCTION fixture_app_usage_fault(); DROP TABLE fixture_app_usage_fault"
    }
}
