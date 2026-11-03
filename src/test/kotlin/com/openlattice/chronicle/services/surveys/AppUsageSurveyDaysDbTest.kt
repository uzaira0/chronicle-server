package com.openlattice.chronicle.services.surveys

import com.openlattice.chronicle.storage.ResolvedStorage

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.geekbeast.rhizome.KotlinDelegatedStringSet
import com.openlattice.chronicle.contract.seedEnrolledParticipant
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.services.delete.ServerSweepTestFixture
import com.openlattice.chronicle.services.download.DataDownloadService
import com.openlattice.chronicle.services.participantaccess.EnrollmentAttemptRepository
import com.openlattice.chronicle.settings.AppUsageFrequency
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.StudyFeature
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.survey.AppUsageAnswer
import com.openlattice.chronicle.survey.AppUsageDayConflict
import com.openlattice.chronicle.survey.AppUsageDayState
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Survey days end to end on the migrated schema, across the April 2026 fall-back in America/Santiago: the server
 * lists ended days, stores every answer (including "I don't know" and a day with nothing to review), reopens a day for
 * late uploads, and exports one row per day, app and block with the answer lag.
 */
class AppUsageSurveyDaysDbTest : ServerSweepTestFixture() {
    private val zone = ZoneId.of("America/Santiago")
    private val rules = AppUsageDayRules(zone, 60, AppUsageFrequency.DAILY)
    private val enrolledAt = at(2026, 4, 3, 15, 0)
    private val now = at(2026, 4, 7, 10, 0)
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): ZonedDateTime =
        LocalDateTime.of(year, month, day, hour, minute, second).atZone(zone)
    private fun day(date: Int) = LocalDate.of(2026, 4, date)

    private fun sql(pool: HikariDataSource, statement: String, vararg values: Any?) = pool.connection.use { connection ->
        connection.prepareStatement(statement).use { prepared ->
            values.forEachIndexed { index, value -> prepared.setObject(index + 1, value) }
            prepared.executeUpdate()
        }
    }

    private fun session(pool: HikariDataSource, study: UUID, participant: String, app: String, from: ZonedDateTime, to: ZonedDateTime) =
        insertUsage(pool, study, participant, listOf(UsageRow(app, 1, from.toInstant()), UsageRow(app, 2, to.toInstant())))

    private companion object {
        private val INTERACTIONS = mapOf(1 to "Move to Foreground", 2 to "Move to Background", 7 to "User Interaction",
            26 to "Device Shutdown", 27 to "Device Startup")
        private val MONTH_APPS = listOf("brief", "long", "night")
        private val MONTH_READ_BOUND = Duration.ofSeconds(10)
    }

    /** One usage row: [type] 1 foreground, 2 background, 26 shutdown, 27 startup; a legacy row (0) is named by [interaction]. */
    private data class UsageRow(val app: String, val type: Int, val at: Instant, val interaction: String = INTERACTIONS.getValue(type))

    private fun insertUsage(pool: HikariDataSource, study: UUID, participant: String, rows: List<UsageRow>) =
        pool.connection.use { connection ->
            connection.prepareStatement("""
                INSERT INTO chronicle_usage_events (study_id, participant_id, app_package_name, application_label, interaction_type,
                    event_type, event_timestamp, timezone, username, uploaded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, '', now())
            """.trimIndent()).use { statement ->
                rows.forEach { row ->
                    statement.setString(1, study.toString())
                    statement.setString(2, participant)
                    statement.setString(3, row.app)
                    statement.setString(4, row.app.substringAfterLast('.'))
                    statement.setString(5, row.interaction)
                    statement.setInt(6, row.type)
                    statement.setObject(7, row.at.atOffset(ZoneOffset.UTC))
                    statement.setString(8, zone.id)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }

    private fun answer(app: String, date: LocalDate, users: List<String>) =
        AppUsageAnswer(app, date.atStartOfDay(zone).toOffsetDateTime(), users)

    /** Enrolls [participant] on a device of a shared-device study: the study collects user identification. */
    private fun enrollOnASharedDevice(pool: HikariDataSource, study: UUID, participant: String, at: ZonedDateTime = enrolledAt) {
        sql(pool,
            "UPDATE studies SET settings = ?::jsonb, modules = jsonb_build_object(?::text, '{}'::jsonb) WHERE study_id = ?",
            ObjectMappers.newJsonMapper().writeValueAsString(StudySettings(mapOf(StudySettingType.DataCollection to
                AndroidDataCollectionSetting(mapOf(CollectionModuleId.USER_IDENTIFICATION to CollectionModuleSetting(enabled = true)),
                    settingsVersion = 2)))),
            StudyFeature.CHRONICLE_DATA_COLLECTION.name, study)
        sql(pool, """
            INSERT INTO api_keys (key_id, study_id, key_hash, key_prefix, scope, created_by, created_at, expires_at,
                participant_id, random_device_id)
            VALUES (?, ?, ?, 'test', 'WRITE', 'survey-days', ?, now() + interval '1 day', ?, ?)
        """.trimIndent(), UUID.randomUUID(), study, UUID.randomUUID().toString(), at.toOffsetDateTime(),
            participant, UUID.randomUUID())
    }

    private fun storage(study: UUID, pool: HikariDataSource): StorageResolver = mock<StorageResolver>().also { storage ->
        whenever(storage.getPlatformStorage()).thenReturn(pool)
        whenever(storage.getPlatformReadStorage()).thenReturn(pool)
        whenever(storage.resolveAndGetFlavor(study)).thenReturn(ResolvedStorage(PostgresFlavor.VANILLA, pool))
    }

    /** [excluded] are the study's filtered apps, keyed by [study]. */
    private fun surveys(storage: StorageResolver, at: ZonedDateTime = now, study: UUID? = null,
                        excluded: Set<String> = emptySet()): SurveysService {
        val hazelcast = mock<HazelcastInstance>()
        val filtered = mock<IMap<UUID, KotlinDelegatedStringSet>>()
        if (study != null) whenever(filtered[study]).thenReturn(KotlinDelegatedStringSet(excluded))
        whenever(hazelcast.getMap<UUID, KotlinDelegatedStringSet>("FILTERED_APPS")).thenReturn(filtered)
        return SurveysService(hazelcast, storage, mock(), mock(), mock(), mock(), Clock.fixed(at.toInstant(), zone))
    }

    private fun hex64() = UUID.randomUUID().toString().replace("-", "").repeat(2)

    /** A phone's attempt that issued its key at [issuedAt], then the study team cancelled it. */
    private fun cancelledAttempt(pool: HikariDataSource, study: UUID, participant: String, issuedAt: ZonedDateTime) {
        val keyHash = hex64()
        val accessCode = UUID.randomUUID()
        val issued = issuedAt.toOffsetDateTime()
        sql(pool, """
            INSERT INTO api_keys (key_id, study_id, key_hash, key_prefix, scope, created_by, created_at, expires_at,
                participant_id, random_device_id)
            VALUES (?, ?, ?, 'test', 'WRITE', 'survey-days', ?, now() + interval '1 day', ?, ?)
        """.trimIndent(), UUID.randomUUID(), study, keyHash, issued, participant, UUID.randomUUID())
        sql(pool, """
            INSERT INTO participant_form_access_codes (access_code_id, token_hash, study_id, participant_id, form_kind,
                issuer_type, issued_by, expires_at, exchanged_at, enrollment_attempt_id, enrollment_source_device_hash,
                enrollment_random_device_id, enrollment_manifest_digest, enrollment_request_hash,
                enrollment_proposed_key_hash, enrollment_replay_expires_at)
            VALUES (?, ?, ?, ?, 'ENROLLMENT', 'RESEARCHER', 'survey-days', ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(), accessCode, hex64().toByteArray(), study, participant, issued.plusDays(7), issued,
            UUID.randomUUID(), hex64(), UUID.randomUUID(), hex64(), hex64(), keyHash, issued.plusDays(1))
        pool.connection.use { connection ->
            connection.autoCommit = false
            val cancelled = EnrollmentAttemptRepository(connection)
                .cancelAttempts(study, participant, setOf(accessCode), issued.plusHours(1))
            connection.commit()
            assertEquals(1, cancelled.newlyCancelled)
        }
    }

    /** The participant's own study context, as the survey page and the reminder manifest read it (RLS applies). */
    private fun participantContextPool(name: String, study: UUID): HikariDataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = postgres.jdbcUrl
        username = postgres.username
        password = postgres.password
        maximumPoolSize = 2
        addDataSourceProperty("ApplicationName", name)
        connectionInitSql = "SET ROLE chronicle_app; SELECT set_config('app.authorized_studies', '$study', false)"
    })

    /** The first survey day is the day of the enrollment in force, not of a cancelled earlier attempt. */
    @Test fun aCancelledEarlierAttemptAddsNoSurveyDay() {
        val study = UUID.randomUUID()
        val participant = "survey-cancelled-attempt"
        seed(study, participant)
        pool("survey-cancelled-attempt").use { pool ->
            cancelledAttempt(pool, study, participant, at(2026, 4, 2, 10, 0))
            enrollOnASharedDevice(pool, study, participant)
            participantContextPool("survey-cancelled-attempt-reader", study).use { reader ->
                val days = checkNotNull(surveys(storage(study, reader)).getAppUsageDays(study, participant, rules)).days
                assertEquals((3..6).map(::day), days.map { it.date })
            }
        }
    }

    /**
     * The daily survey is for shared devices only: in a study that does not collect user
     * identification the participant has no survey days, and a submission (an old link) is refused and stores nothing.
     */
    @Test fun aStudyWithoutSharedDevicesHasNoSurveyAndRefusesSubmissions() {
        val study = UUID.randomUUID()
        val participant = "survey-single-user"
        seed(study, participant)
        pool("survey-single-user").use { pool ->
            session(pool, study, participant, "a.maps", at(2026, 4, 4, 10, 0), at(2026, 4, 4, 11, 0))
            val surveys = surveys(storage(study, pool))
            assertEquals(null, surveys.getAppUsageDays(study, participant, rules))
            val refused = assertThrows(com.openlattice.chronicle.exceptions.CollectionUnavailableException::class.java) {
                surveys.submitAppUsageDay(study, participant, day(4), listOf(answer("a.maps", day(4), listOf("Child"))), rules)
            }
            assertEquals(com.openlattice.chronicle.exceptions.CollectionUnavailableException.Scope.PARTICIPANT, refused.scope)
            assertNoSurveyRows(pool, study)
        }
    }

    private fun assertNoSurveyRows(pool: HikariDataSource, study: UUID) = pool.connection.use { connection ->
        connection.prepareStatement("SELECT count(*) FROM app_usage_survey WHERE study_id::text = ?").use { statement ->
            statement.setString(1, study.toString())
            statement.executeQuery().use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) }
        }
    }

    @Test fun surveyDaysFromEnrollmentToTheServerDay() {
        val study = UUID.randomUUID()
        val participant = "survey-days"
        seed(study, participant)
        pool("survey-days").use { pool ->
            enrollOnASharedDevice(pool, study, participant)
            session(pool, study, participant, "a.maps", at(2026, 4, 3, 23, 30), at(2026, 4, 4, 0, 45))
            session(pool, study, participant, "c.chat", at(2026, 4, 4, 10, 0), at(2026, 4, 4, 10, 0, 59))
            session(pool, study, participant, "b.video", at(2026, 4, 6, 8, 0), at(2026, 4, 6, 8, 1))
            val storage = storage(study, pool)
            val surveys = surveys(storage)
            fun states() = checkNotNull(surveys.getAppUsageDays(study, participant, rules)).days.associate { it.date to it.state }
            fun conflict(date: LocalDate, answers: List<AppUsageAnswer>) = assertThrows(AppUsageDayConflictException::class.java) {
                surveys.submitAppUsageDay(study, participant, date, answers, rules)
            }.conflict

            assertEquals(day(7), surveys.getAppUsageDays(study, participant, rules)?.today)
            assertEquals((3..6).associate { day(it) to AppUsageDayState.UNANSWERED }, states())
            // Use across midnight counts on both days; 59 seconds is hidden.
            val fourth = surveys.getAppUsageDay(study, participant, day(4), rules)
            assertEquals(listOf("a.maps"), fourth.apps.map { it.appPackageName })
            assertEquals(listOf(day(4).atStartOfDay(zone).toOffsetDateTime()), fourth.apps.single().blocks.map { it.start })
            assertEquals(listOf("a.maps"), surveys.getAppUsageDay(study, participant, day(3), rules).apps.map { it.appPackageName })
            assertEquals(AppUsageDayConflict.DAY_UNAVAILABLE, assertThrows(AppUsageDayConflictException::class.java) {
                surveys.getAppUsageDay(study, participant, day(7), rules)
            }.conflict)

            // "I don't know" and a day with nothing to review both leave a stored record.
            surveys.submitAppUsageDay(study, participant, day(4), listOf(answer("a.maps", day(4), listOf("I don't know"))), rules)
            surveys.submitAppUsageDay(study, participant, day(5), emptyList(), rules)
            assertEquals(AppUsageDayConflict.ALREADY_ANSWERED, conflict(day(4), listOf(answer("a.maps", day(4), listOf("Child")))))
            assertEquals(AppUsageDayConflict.ALREADY_ANSWERED, conflict(day(5), emptyList()))
            assertEquals(AppUsageDayConflict.APPS_CHANGED, conflict(day(3), emptyList()))
            assertEquals(AppUsageDayConflict.APPS_CHANGED, conflict(day(6), listOf(answer("c.chat", day(6), listOf("Child")))))
            assertEquals(AppUsageDayConflict.DAY_UNAVAILABLE, conflict(day(7), emptyList()))
            assertEquals(mapOf(day(3) to AppUsageDayState.UNANSWERED, day(4) to AppUsageDayState.ANSWERED,
                day(5) to AppUsageDayState.ANSWERED, day(6) to AppUsageDayState.UNANSWERED), states())

            // A late upload reopens only its own day, and asks only about the new app.
            session(pool, study, participant, "c.chat", at(2026, 4, 4, 12, 0), at(2026, 4, 4, 12, 5))
            assertEquals(AppUsageDayState.NEW_APPS, states()[day(4)])
            val reopened = surveys.getAppUsageDay(study, participant, day(4), rules).apps
                .associate { it.appPackageName to it.blocks.single().users }
            assertEquals(mapOf("a.maps" to listOf("I don't know"), "c.chat" to null), reopened)
            surveys.submitAppUsageDay(study, participant, day(4), listOf(answer("c.chat", day(4), listOf("Child"))), rules)
            assertEquals(AppUsageDayState.ANSWERED, states()[day(4)])

            val rows = DataDownloadService(storage).getParticipantsAppUsageSurveyData(study, setOf(participant),
                day(1).atStartOfDay(zone).toOffsetDateTime(), day(8).atStartOfDay(zone).toOffsetDateTime(), zone).toList()
            fun lag(date: LocalDate) = Duration.between(date.plusDays(1).atStartOfDay(zone), now).seconds
            assertEquals(setOf(
                Triple("2026-04-04", "a.maps", lag(day(4))),
                Triple("2026-04-04", "c.chat", lag(day(4))),
                Triple("2026-04-05", "", lag(day(5))),
            ), rows.map { Triple(it["survey_date"], it["app_package_name"], (it["answer_lag_seconds"] as Number).toLong()) }.toSet())
            assertEquals(3, rows.size)
            assertEquals(setOf(zone.id), rows.map { it["timezone"] }.toSet())
            // a4-export-4: each row carries the receipt instant in the study zone, so it is the Santiago day it was answered.
            assertEquals(setOf(now.toOffsetDateTime()), rows.map { it["submission_date"] }.toSet())
            assertEquals(setOf(day(7)), rows.map { (it["submission_date"] as java.time.OffsetDateTime).toLocalDate() }.toSet())

            // After the study zone is corrected, a range of days still selects the stored survey days, not the
            // rows whose old-zone day start happens to fall inside the new zone's range.
            val corrected = ZoneId.of("America/Los_Angeles")
            val afterCorrection = DataDownloadService(storage).getParticipantsAppUsageSurveyData(study, setOf(participant),
                day(4).atStartOfDay(corrected).toOffsetDateTime(), day(5).atStartOfDay(corrected).toOffsetDateTime(),
                corrected).map { it["survey_date"] }
            assertEquals(listOf("2026-04-04", "2026-04-04"), afterCorrection)
        }
    }

    /**
     * Each branch of the five transition queries (a carried-in session, the foreground-last tie, the last
     * restart before the day, restarts and legacy rows inside it, the first close and the first restart after it), the
     * threshold, filtered apps and another participant's rows, read from the database on both 2026 America/Santiago
     * transition days and compared with hour blocks worked out by hand.
     */
    @Test fun databaseTransitionsMatchHandWorkedSessions() {
        val hourly = AppUsageDayRules(zone, 60, AppUsageFrequency.HOURLY)
        for ((date, hours) in listOf(LocalDate.of(2026, 4, 4) to 25, LocalDate.of(2026, 9, 6) to 23)) {
            val start = date.atStartOfDay(zone).toInstant()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant()
            assertEquals(hours.toLong(), Duration.between(start, end).toHours())
            fun s(hour: Long, minute: Long = 0, second: Long = 0): Instant =
                start + Duration.ofHours(hour).plusMinutes(minute).plusSeconds(second)
            fun e(hour: Long, minute: Long = 0): Instant = end + Duration.ofHours(hour).plusMinutes(minute)
            val last = hours - 1
            fun row(app: String, type: Int, at: Instant) = UsageRow(app, type, at)
            val cases: Map<String, Pair<List<UsageRow>, Map<String, List<Int>>>> = mapOf(
                "carried" to (listOf(row("o.carry", 1, s(-2)), row("o.carry", 2, s(0, 2)),
                    row("o.long", 1, s(-72)), row("o.long", 2, e(1))) to
                    mapOf("o.carry" to listOf(0), "o.long" to (0 until hours).toList())),
                // An activity switch: background and foreground at one instant before the day leave the app open.
                "carried-switch" to (listOf(row("o.switch", 1, s(-2)), row("o.switch", 2, s(-1)), row("o.switch", 1, s(-1)),
                    row("o.switch", 2, s(0, 5))) to mapOf("o.switch" to listOf(0))),
                // Only the last restart before the day counts: the startup drops the earlier session, not the later one.
                "restart-before" to (listOf(row("android", 26, s(-6)), row("o.dropped", 1, s(-1)),
                    row("android", 27, s(0, -30)), row("o.kept", 1, s(0, -20)), row("o.dropped", 2, s(0, 10)),
                    row("o.kept", 2, s(0, 10))) to mapOf("o.kept" to listOf(0))),
                "inside" to (listOf(row("o.closed", 1, s(10)), row("android", 26, s(10, 30)), row("o.closed", 2, s(11)),
                    row("o.power", 1, s(12)), row("android", 27, s(12, 20)), row("o.power", 2, s(13)),
                    UsageRow("o.legacy", 0, s(15), "Move to Foreground"), UsageRow("o.legacy", 0, s(15, 10), "Move to Background"),
                    row("o.short", 1, s(16)), row("o.short", 2, s(16, 0, 59)),
                    row("o.exact", 1, s(17)), row("o.exact", 2, s(17, 1)),
                    row("o.filtered", 1, s(18)), row("o.filtered", 2, s(18, 30))) to
                    mapOf("o.closed" to listOf(10), "o.legacy" to listOf(15), "o.exact" to listOf(17))),
                // The first event after the day closes the session; at a tie the background comes first.
                "following" to (listOf(row("o.next", 1, e(-2)), row("o.next", 2, e(2)), row("o.next", 1, e(3)),
                    row("o.switch", 1, e(-1)), row("o.switch", 2, e(1)), row("o.switch", 1, e(1))) to
                    mapOf("o.next" to listOf(last - 1, last), "o.switch" to listOf(last))),
                "following-restart" to (listOf(row("o.tail", 1, e(0, -10)), row("android", 26, e(0, 30))) to
                    mapOf("o.tail" to listOf(last))),
                "isolated" to (listOf(row("o.own", 1, s(5)), row("o.own", 2, s(6))) to mapOf("o.own" to listOf(5))),
            )
            val study = UUID.randomUUID()
            val participants = cases.keys.associateWith { "$it-$date" }
            seed(study, participants.values.first())
            postgres.createConnection("").use { connection ->
                participants.values.drop(1).forEach { seedEnrolledParticipant(connection, study, it) }
            }
            pool("survey-transitions").use { pool ->
                cases.forEach { (name, case) ->
                    enrollOnASharedDevice(pool, study, participants.getValue(name), start.minus(Duration.ofDays(10)).atZone(zone))
                    insertUsage(pool, study, participants.getValue(name), case.first)
                }
                insertUsage(pool, study, "isolated-other-$date", listOf(row("o.foreign", 1, s(-1)), row("o.foreign", 2, e(1))))
                val surveys = surveys(storage(study, pool), end.atZone(zone).plusDays(2), study, setOf("o.filtered"))
                cases.forEach { (name, case) ->
                    val listed = surveys.getAppUsageDay(study, participants.getValue(name), date, hourly).apps.associate { app ->
                        app.appPackageName to app.blocks.map { Duration.between(start, it.start.toInstant()).toHours().toInt() }
                    }
                    assertEquals("$name on $date", case.second, listed)
                }
            }
        }
    }

    /**
     * The study zone is corrected from UTC to America/Santiago and no usage row moves. Each answer keeps its
     * survey day: answered days stay answered, a repeat answer is still a conflict, the export keeps the survey date and the
     * answer lag of the zone the answer was given in, and an app a corrected day newly lists is asked about. The usage sits
     * next to both 2026 Santiago transitions, where the two zones disagree on the date.
     */
    @Test fun zoneCorrectionKeepsPersistedDailyAnswersConsistent() {
        val study = UUID.randomUUID()
        val participant = "zone-correction"
        val asked = ZonedDateTime.of(2026, 9, 10, 12, 0, 0, 0, zone)
        val wrong = AppUsageDayRules(ZoneId.of("UTC"), 60, AppUsageFrequency.DAILY)
        val corrected = AppUsageDayRules(zone, 60, AppUsageFrequency.DAILY)
        fun date(text: String) = LocalDate.parse(text)
        seed(study, participant)
        pool("survey-zone-correction").use { pool ->
            enrollOnASharedDevice(pool, study, participant, Instant.parse("2026-04-01T00:00:00Z").atZone(zone))
            fun used(app: String, from: String, to: String) = insertUsage(pool, study, participant,
                listOf(UsageRow(app, 1, Instant.parse(from)), UsageRow(app, 2, Instant.parse(to))))
            used("o.maps", "2026-04-04T15:00:00Z", "2026-04-04T15:30:00Z") // 4 April in both zones
            used("o.late", "2026-04-05T02:00:00Z", "2026-04-05T02:30:00Z") // 4 April 23:00 in Santiago, 5 April in UTC
            used("o.legacy", "2026-04-06T01:50:00Z", "2026-04-06T02:10:00Z") // 5 April in Santiago, 6 April in UTC
            used("o.video", "2026-09-06T03:30:00Z", "2026-09-06T03:50:00Z") // 5 September in Santiago, 6 September in UTC
            val storage = storage(study, pool)
            val surveys = surveys(storage, asked)
            fun users(day: String, rules: AppUsageDayRules) = surveys.getAppUsageDay(study, participant, date(day), rules).apps
                .associate { it.appPackageName to it.blocks.single().users }
            fun answer(day: String, rules: AppUsageDayRules, app: String, users: List<String>) = surveys.submitAppUsageDay(
                study, participant, date(day),
                listOf(AppUsageAnswer(app, date(day).atStartOfDay(rules.zone).toOffsetDateTime(), users)), rules)

            assertEquals(mapOf("o.maps" to null), users("2026-04-04", wrong))
            assertEquals(mapOf("o.late" to null), users("2026-04-05", wrong))
            assertEquals(mapOf("o.video" to null), users("2026-09-06", wrong))
            answer("2026-04-04", wrong, "o.maps", listOf("Child"))
            answer("2026-04-05", wrong, "o.late", listOf("Parent"))
            answer("2026-09-06", wrong, "o.video", listOf("Child"))
            // A row written before survey days existed belongs to the day of its usage instant in the zone in force.
            sql(pool, """
                INSERT INTO app_usage_survey (study_id, participant_id, submission_date, application_label, app_package_name,
                    event_timestamp, timezone, users, survey_date)
                VALUES (?, ?, ?, 'legacy', 'o.legacy', ?, 'UTC', ARRAY['Sibling'], NULL)
            """.trimIndent(), study, participant, asked.toOffsetDateTime(),
                Instant.parse("2026-04-06T02:00:00Z").atOffset(ZoneOffset.UTC))

            assertEquals(mapOf("o.maps" to listOf("Child"), "o.late" to null), users("2026-04-04", corrected))
            assertEquals(mapOf("o.legacy" to listOf("Sibling")), users("2026-04-05", corrected))
            assertEquals(mapOf("o.video" to null), users("2026-09-05", corrected))
            assertEquals(emptyMap<String, List<String>?>(), users("2026-09-06", corrected))
            val watched = listOf("2026-04-04", "2026-04-05", "2026-09-05", "2026-09-06").map(::date)
            fun states() = checkNotNull(surveys.getAppUsageDays(study, participant, corrected)).days
                .associate { it.date to it.state }.filterKeys { it in watched }
            assertEquals(mapOf(watched[0] to AppUsageDayState.NEW_APPS, watched[1] to AppUsageDayState.ANSWERED,
                watched[2] to AppUsageDayState.UNANSWERED, watched[3] to AppUsageDayState.ANSWERED), states())
            assertEquals(AppUsageDayConflict.ALREADY_ANSWERED, assertThrows(AppUsageDayConflictException::class.java) {
                answer("2026-04-04", corrected, "o.maps", listOf("Parent"))
            }.conflict)
            answer("2026-04-04", corrected, "o.late", listOf("Parent"))
            assertEquals(mapOf("o.maps" to listOf("Child"), "o.late" to listOf("Parent")), users("2026-04-04", corrected))
            assertEquals(AppUsageDayState.ANSWERED, states()[watched[0]])

            fun lag(dayEnd: String) = Duration.between(Instant.parse(dayEnd), asked.toInstant()).seconds
            fun exported(from: String, to: String) = DataDownloadService(storage).getParticipantsAppUsageSurveyData(study,
                setOf(participant), date(from).atStartOfDay(zone).toOffsetDateTime(), date(to).atStartOfDay(zone).toOffsetDateTime(),
                zone).map { Triple(it["survey_date"], it["app_package_name"], (it["answer_lag_seconds"] as Number).toLong()) }
            assertEquals(listOf(
                Triple("2026-04-04", "o.late", lag("2026-04-05T04:00:00Z")),
                Triple("2026-04-04", "o.maps", lag("2026-04-05T00:00:00Z")),
                Triple("2026-04-05", "o.late", lag("2026-04-06T00:00:00Z")),
                Triple("2026-09-06", "o.video", lag("2026-09-07T00:00:00Z")),
            ), exported("2026-04-01", "2026-09-10").sortedBy { "${it.first} ${it.second}" })
            assertEquals(setOf("o.maps", "o.late"), exported("2026-04-04", "2026-04-05").map { it.second }.toSet())
        }
    }

    /**
     * A month of a 25-phone shared-device study at the pilot's volume (a usage row every 15 minutes around
     * the app sessions). Every participant's day list, which the reminder manifest reads too, and every day's detail stay
     * complete, isolated and stable, each read well inside its time bound.
     */
    @Test fun pilotMonthSurveyListingsRemainComplete() {
        val study = UUID.randomUUID()
        val first = LocalDate.of(2026, 3, 20) // the month spans the 4 April 2026 fall-back
        val month = SurveyMonth((0L until 30L).map(first::plusDays))
        val participants = (1..25).map { "month-%02d".format(it) }
        seed(study, participants.first())
        postgres.createConnection("").use { connection ->
            participants.drop(1).forEach { seedEnrolledParticipant(connection, study, it) }
        }
        pool("survey-month").use { pool ->
            participants.forEach { participant ->
                enrollOnASharedDevice(pool, study, participant, first.atStartOfDay(zone))
                insertUsage(pool, study, participant, month.days.flatMap { monthUsage(participant, it) })
            }
            val surveys = surveys(storage(study, pool), first.plusDays(30).atTime(6, 0).atZone(zone))
            participants.forEach { participant ->
                month.answered.forEach { day ->
                    surveys.submitAppUsageDay(study, participant, day, MONTH_APPS.map {
                        AppUsageAnswer("$participant.$it", day.atStartOfDay(zone).toOffsetDateTime(), listOf("Child"))
                    }, month.rules)
                }
                val start = month.late.atStartOfDay(zone).toInstant()
                insertUsage(pool, study, participant, listOf(UsageRow("$participant.late", 1, start + Duration.ofHours(20)),
                    UsageRow("$participant.late", 2, start + Duration.ofHours(20).plusMinutes(5))))
            }
            participants.forEach { assertMonthListed(surveys, study, it, month) }
        }
    }

    /** Thirty survey days: days 1..10 answered, and an app first used on day 5 after its answers. */
    private inner class SurveyMonth(val days: List<LocalDate>) {
        val rules = AppUsageDayRules(zone, 60, AppUsageFrequency.DAILY)
        val answered = days.subList(1, 11).toSet()
        val late = days[5]
        val today: LocalDate = days.last().plusDays(1)

        fun state(day: LocalDate) = when (day) {
            late -> AppUsageDayState.NEW_APPS
            in answered -> AppUsageDayState.ANSWERED
            else -> AppUsageDayState.UNANSWERED
        }

        fun apps(participant: String, day: LocalDate): Map<String, List<String>?> {
            val users = if (day in answered) listOf("Child") else null
            return MONTH_APPS.associate { "$participant.$it" to users } +
                (if (day == late) mapOf("$participant.late" to null) else emptyMap())
        }
    }

    /** A filler row every 15 minutes plus short, brief, long and overnight sessions. */
    private fun monthUsage(participant: String, day: LocalDate): List<UsageRow> {
        fun app(name: String) = "$participant.$name"
        val start = day.atStartOfDay(zone).toInstant()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        fun at(hour: Long, minute: Long = 0, second: Long = 0) = start + Duration.ofHours(hour).plusMinutes(minute).plusSeconds(second)
        val filler = (0L until Duration.between(start, end).toMinutes() step 15L).map { UsageRow(app("brief"), 7, at(0, it)) }
        return filler + listOf(
            UsageRow(app("short"), 1, at(8)), UsageRow(app("short"), 2, at(8, 0, 30)),
            UsageRow(app("brief"), 1, at(9)), UsageRow(app("brief"), 2, at(9, 1, 30)),
            UsageRow(app("long"), 1, at(13)), UsageRow(app("long"), 2, at(13, 10)),
            UsageRow(app("night"), 1, end.minus(Duration.ofMinutes(10))), UsageRow(app("night"), 2, end.plus(Duration.ofMinutes(10))),
        )
    }

    /** The day list (which the reminder manifest reads too) and every day's detail: complete, stable and in time. */
    private fun assertMonthListed(surveys: SurveysService, study: UUID, participant: String, month: SurveyMonth) {
        val listed = withinReadBound("$participant days") { checkNotNull(surveys.getAppUsageDays(study, participant, month.rules)) }
        assertEquals(month.today, listed.today)
        assertEquals(month.days.associateWith(month::state), listed.days.associate { it.date to it.state })
        assertEquals(month.days, listed.days.map { it.date })
        month.days.forEach { day ->
            val apps = withinReadBound("$participant $day") { surveys.getAppUsageDay(study, participant, day, month.rules) }.apps
                .associate { it.appPackageName to it.blocks.single().users }
            assertEquals("$participant $day", month.apps(participant, day), apps)
        }
        assertEquals(listed, surveys.getAppUsageDays(study, participant, month.rules))
    }

    private fun <T> withinReadBound(what: String, read: () -> T): T {
        val started = System.nanoTime()
        return read().also {
            val took = Duration.ofNanos(System.nanoTime() - started)
            assertTrue("$what took $took", took < MONTH_READ_BOUND)
        }
    }
}
