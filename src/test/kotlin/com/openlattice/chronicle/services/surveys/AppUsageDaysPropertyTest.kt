package com.openlattice.chronicle.services.surveys

import com.openlattice.chronicle.settings.AppUsageFrequency
import com.openlattice.chronicle.storage.SurveyEnrollment
import com.openlattice.chronicle.survey.AppUsageAnswer
import com.openlattice.chronicle.survey.AppUsageDayConflict
import com.openlattice.chronicle.survey.AppUsageDayState
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * Generated usage timelines in America/Santiago across both DST transitions. The generator records what really
 * happened (sessions); the events are derived from it, with the losses phones produce. Expected values come from the
 * sessions, never from the code under test.
 */
class AppUsageDaysPropertyTest {
    private val zone = ZoneId.of("America/Santiago")
    private val apps = listOf("a.maps", "b.video", "c.chat", "d.game")

    @Test fun answerRowsKeepNamedCaptureFieldsAndTheEmptyDaySentinel() {
        val start = Instant.parse("2026-10-01T12:00:00Z")
        val app = ListedApp("a.maps", "Maps", listOf(start..<start.plusSeconds(3600)))
        val answers = listOf(AppUsageAnswer(app.app, start.atZone(zone).toOffsetDateTime(), listOf("Child alone")))
        val rows: List<AppUsageAnswerRow> = AppUsageDays.rows(listOf(app), emptyList(), answers, start)
        assertEquals(listOf(AppUsageAnswerRow(app, start, listOf("Child alone"))), rows)
        assertEquals(listOf(AppUsageAnswerRow(null, start, emptyList())),
            AppUsageDays.rows(emptyList(), emptyList(), emptyList(), start))
    }

    /** Spring-forward (skipped midnight) and fall-back (repeated hour) dates of 2026, from the zone rules. */
    private val dstDates: List<LocalDate> =
        generateSequence(zone.rules.nextTransition(Instant.parse("2026-01-01T00:00:00Z"))) { zone.rules.nextTransition(it.instant) }
            .takeWhile { it.instant.atZone(zone).year == 2026 }
            .map { it.instant.atZone(zone).toLocalDate() }
            .toList()

    private data class Session(val app: String, val start: Instant, val end: Instant, val counted: Boolean)

    private data class Timeline(val sessions: List<Session>, val events: List<UsageTransition>, val from: Instant, val to: Instant)

    private fun timeline(random: Random): Timeline {
        val anchor = (dstDates + LocalDate.of(2026, 6, 15)).random(random)
        var at = anchor.minusDays(2).atStartOfDay(zone).toInstant().plusSeconds(random.nextLong(0, 86_400))
        val from = at
        val sessions = mutableListOf<Session>()
        val events = mutableListOf<UsageTransition>()
        fun event(app: String, time: Instant, kind: UsageTransitionKind) = events.add(UsageTransition(app, app.uppercase(), time, kind))
        repeat(random.nextInt(1, 40)) {
            at = at.plusSeconds(when (random.nextInt(4)) {
                0 -> 0L // back-to-back: one app's pause and the next one's resume share an instant
                1 -> random.nextLong(1, 600)
                2 -> random.nextLong(600, 6 * 3600)
                else -> random.nextLong(20 * 3600, 50 * 3600) // skipped days
            })
            val app = apps.random(random)
            val length = when (random.nextInt(4)) {
                0 -> random.nextLong(1, 120)
                1, 2 -> random.nextLong(120, 3 * 3600)
                else -> random.nextLong(20 * 3600, 50 * 3600) // a whole day in one app
            }
            val end = at.plusSeconds(length)
            when (random.nextInt(10)) {
                0 -> { // the phone floor or a suspension dropped the resume: nothing tells when use began
                    event(app, end, UsageTransitionKind.BACKGROUND)
                    sessions.add(Session(app, at, end, counted = false))
                }
                1 -> { // power loss: no pause, no shutdown; the next boot drops the session
                    event(app, at, UsageTransitionKind.FOREGROUND)
                    event("android", end.plusSeconds(random.nextLong(1, 600)), UsageTransitionKind.STARTUP)
                    sessions.add(Session(app, at, end, counted = false))
                }
                2 -> { // an orderly shutdown ends the session
                    event(app, at, UsageTransitionKind.FOREGROUND)
                    event("android", end, UsageTransitionKind.SHUTDOWN)
                    event("android", end.plusSeconds(random.nextLong(1, 600)), UsageTransitionKind.STARTUP)
                    sessions.add(Session(app, at, end, counted = true))
                }
                else -> {
                    event(app, at, UsageTransitionKind.FOREGROUND)
                    event(app, end, UsageTransitionKind.BACKGROUND)
                    sessions.add(Session(app, at, end, counted = true))
                }
            }
            at = events.maxOf { it.at }
        }
        return Timeline(sessions, events.shuffled(random), from, at.plusSeconds(3600))
    }

    private fun overlap(start: Instant, end: Instant, window: OpenEndRange<Instant>): Duration {
        val from = maxOf(start, window.start)
        val to = minOf(end, window.endExclusive)
        return if (from < to) Duration.between(from, to) else Duration.ZERO
    }

    private fun expected(timeline: Timeline, window: OpenEndRange<Instant>): Map<String, Duration> =
        timeline.sessions.filter { it.counted }.groupBy { it.app }
            .mapValues { (_, sessions) -> sessions.fold(Duration.ZERO) { sum, session -> sum + overlap(session.start, session.end, window) } }
            .filterValues { !it.isZero }

    /**
     * What the database hands over: each app's last event before the window and first one from its end, the restarts
     * next to the window, and the window's own events.
     */
    private fun queried(events: List<UsageTransition>, window: OpenEndRange<Instant>): List<UsageTransition> {
        val before = events.filter { it.at < window.start }
        val after = events.filter { it.at >= window.endExclusive }
        val restarts = setOf(UsageTransitionKind.SHUTDOWN, UsageTransitionKind.STARTUP)
        val order = compareBy<UsageTransition>({ it.at }, { it.kind })
        val lastPerApp = before.filter { it.kind !in restarts }.groupBy { it.app }.values.map { it.maxWith(order) }
        val nextPerApp = after.filter { it.kind !in restarts }.groupBy { it.app }.values.map { it.minWith(order) }
        val lastRestart = before.filter { it.kind in restarts }.maxWithOrNull(order)
        val nextRestart = after.filter { it.kind in restarts }.minWithOrNull(order)
        return lastPerApp + nextPerApp + listOfNotNull(lastRestart, nextRestart) +
            events.filter { it.at >= window.start && it.at < window.endExclusive }
    }

    private fun measured(events: List<UsageTransition>, window: OpenEndRange<Instant>): Map<String, Duration> =
        AppUsageDays.foreground(queried(events, window), window)
            .mapValues { (_, intervals) -> intervals.fold(Duration.ZERO) { sum, range -> sum + Duration.between(range.start, range.endExclusive) } }

    private fun property(iterations: Int = 2_000, check: (Random) -> Unit) {
        runBlocking { checkAll(iterations, Arb.long()) { seed -> check(Random(seed)) } }
    }

    @Test fun everyDayCountsExactlyTheForegroundTimeInsideIt() = property { random ->
        val timeline = timeline(random)
        val enrollment = SurveyEnrollment(timeline.from, null, emptyList())
        val first = timeline.from.atZone(zone).toLocalDate()
        val last = timeline.to.atZone(zone).toLocalDate()
        generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.forEach { date ->
            val window = checkNotNull(AppUsageDays.window(date, zone, enrollment))
            assertEquals("$date in $timeline", expected(timeline, window), measured(timeline.events, window))
        }
    }

    /**
     * The phone has uploaded only up to [cut]: a session it opened but has not yet closed counts nothing, since the
     * server cannot tell how long it lasted, on its day and on every day after.
     */
    @Test fun aSessionWhoseEndIsNotUploadedYetIsNotCountedPastTheLastEvent() = property { random ->
        val timeline = timeline(random)
        val cut = timeline.from.plusSeconds(random.nextLong(0, Duration.between(timeline.from, timeline.to).seconds + 1))
        val uploaded = timeline.events.filter { it.at <= cut }
        val known = timeline.copy(sessions = timeline.sessions.filter { it.end <= cut })
        val enrollment = SurveyEnrollment(timeline.from, null, emptyList())
        generateSequence(timeline.from.atZone(zone).toLocalDate()) { it.plusDays(1) }
            .takeWhile { it <= timeline.to.atZone(zone).toLocalDate() }
            .forEach { date ->
                val window = checkNotNull(AppUsageDays.window(date, zone, enrollment))
                assertEquals("$date cut at $cut in $timeline", expected(known, window), measured(uploaded, window))
            }
    }

    /**
     * A pause inside the timeline: the phone drops every event inside it, so a session open at its start counts only up
     * to it, and one that began inside it counts nothing, even though its closing event after the resume uploads.
     */
    @Test fun aPauseEndsEverySessionAtItsStart() = property { random ->
        val timeline = timeline(random)
        val length = Duration.between(timeline.from, timeline.to).seconds
        val pausedAt = timeline.from.plusSeconds(random.nextLong(0, length))
        val pause = pausedAt..<pausedAt.plusSeconds(random.nextLong(1, maxOf(2, length / 3)))
        // A power loss shortly before the pause looks to the server like an app still open when the pause began
        // (its restart is inside the pause or after it); nothing tells the two apart, so no answer is right.
        val ambiguous = timeline.sessions.any { session ->
            !session.counted && session.start < pause.start && !session.end.plusSeconds(600).isBefore(pause.start) &&
                timeline.events.any { it.app == session.app && it.at == session.start && it.kind == UsageTransitionKind.FOREGROUND }
        }
        if (ambiguous) return@property
        val uploaded = timeline.events.filterNot { it.at in pause }
        val known = timeline.copy(sessions = timeline.sessions.mapNotNull { session ->
            when {
                session.start >= pause.endExclusive -> session
                session.start >= pause.start -> null
                else -> session.copy(end = minOf(session.end, pause.start))
            }
        })
        val enrollment = SurveyEnrollment(timeline.from, null, listOf(pause))
        generateSequence(timeline.from.atZone(zone).toLocalDate()) { it.plusDays(1) }
            .takeWhile { it <= timeline.to.atZone(zone).toLocalDate() }
            .forEach { date ->
                val window = AppUsageDays.window(date, zone, enrollment) ?: return@forEach
                val measured = AppUsageDays.foreground(queried(uploaded, window), window, enrollment.stops)
                    .mapValues { (_, used) -> used.fold(Duration.ZERO) { sum, range -> sum + Duration.between(range.start, range.endExclusive) } }
                assertEquals("$date paused $pause in $timeline", expected(known, window), measured)
            }
    }

    @Test fun daysAreCalendarDaysOfTheStudyZone() {
        val lengths = dstDates.map { Duration.between(AppUsageDays.bounds(it, zone).start, AppUsageDays.bounds(it, zone).endExclusive) }
        assertEquals(setOf(Duration.ofHours(23), Duration.ofHours(25)), lengths.toSet())
        val ordinary = AppUsageDays.bounds(LocalDate.of(2026, 6, 15), zone)
        assertEquals(Duration.ofHours(24), Duration.between(ordinary.start, ordinary.endExclusive))
        // Consecutive days tile time: no instant falls in two days or in none.
        val dates = generateSequence(LocalDate.of(2026, 1, 1)) { it.plusDays(1) }.take(365).toList()
        dates.zipWithNext().forEach { (day, next) ->
            assertEquals(AppUsageDays.bounds(day, zone).endExclusive, AppUsageDays.bounds(next, zone).start)
        }
        // An HOURLY day has one block per real hour: 23 on the skipped-midnight day, 25 on the repeated-hour day.
        dstDates.zip(lengths).forEach { (date, length) ->
            assertEquals(length.toHours().toInt(), AppUsageDays.blocks(date, zone, AppUsageFrequency.HOURLY).size)
        }
    }

    /** Server clock and phone clock are separate: phone timestamps run ahead of the server's "now". */
    @Test fun onlyEndedDaysAreAnswerableOnTheServerClock() = property { random ->
        val timeline = timeline(random)
        val serverNow = timeline.from.plusSeconds(random.nextLong(0, 6 * 86_400))
        val phoneSkew = Duration.ofSeconds(random.nextLong(-120, 120))
        val skewed = timeline.events.map { it.copy(at = it.at.plus(phoneSkew)) }
        val pausedAt = if (random.nextBoolean()) null else timeline.from.plusSeconds(random.nextLong(1, 5 * 86_400))
        val pause = if (random.nextBoolean()) emptyList() else {
            val start = timeline.from.plusSeconds(random.nextLong(0, 4 * 86_400))
            listOf(start..<start.plusSeconds(random.nextLong(3600, 3 * 86_400)))
        }
        val enrollment = SurveyEnrollment(timeline.from, pausedAt, pause)
        val days = AppUsageDays.endedDays(zone, enrollment, serverNow)
        days.forEach { date ->
            val day = AppUsageDays.bounds(date, zone)
            assertTrue(day.endExclusive <= serverNow)
            val window = checkNotNull(AppUsageDays.window(date, zone, enrollment))
            assertTrue(window.start >= timeline.from && (pausedAt == null || window.endExclusive <= pausedAt))
            assertFalse(pause.any { it.start <= window.start && window.endExclusive <= it.endExclusive })
            AppUsageDays.foreground(queried(skewed, window), window).values.flatten().forEach {
                assertTrue(it.start < it.endExclusive && it.start >= window.start && it.endExclusive <= window.endExclusive)
            }
        }
        // Every ended enrolled day is listed: the first day from enrollment, the stop day up to the stop.
        val expectedDays = generateSequence(timeline.from.atZone(zone).toLocalDate()) { it.plusDays(1) }
            .takeWhile { AppUsageDays.bounds(it, zone).endExclusive <= serverNow }
            .filter { date ->
                val day = AppUsageDays.bounds(date, zone)
                val start = maxOf(day.start, timeline.from)
                val end = pausedAt?.let { minOf(it, day.endExclusive) } ?: day.endExclusive
                start < end && pause.none { it.start <= start && end <= it.endExclusive }
            }.toList()
        assertEquals(expectedDays, days)
    }

    /** Late uploads reopen only their own day, and only for the apps (or hours) they add. */
    @Test fun lateUploadsReopenOnlyTheirDayForTheNewApps() = property(500) { random ->
        val timeline = timeline(random)
        val frequency = if (random.nextBoolean()) AppUsageFrequency.DAILY else AppUsageFrequency.HOURLY
        val enrollment = SurveyEnrollment(timeline.from, null, emptyList())
        val uploadedFirst = timeline.events.filter { random.nextInt(3) != 0 }
        val threshold = 60
        val dates = AppUsageDays.endedDays(zone, enrollment, timeline.to.plus(Duration.ofDays(3)))
        fun listed(events: List<UsageTransition>, date: LocalDate): List<ListedApp> {
            val window = checkNotNull(AppUsageDays.window(date, zone, enrollment))
            return AppUsageDays.listing(AppUsageDays.foreground(queried(events, window), window), emptyMap(), window,
                AppUsageDays.blocks(date, zone, frequency), threshold)
        }
        val answered = dates.flatMap { date ->
            val apps = listed(uploadedFirst, date)
            if (apps.isEmpty()) listOf(StoredAnswer("", AppUsageDays.bounds(date, zone).start, date, zone, emptyList()))
            else apps.flatMap { app -> app.blocks.map { StoredAnswer(app.app, it.start, date, zone, listOf("Child alone")) } }
        }
        dates.forEach { date ->
            val before = listed(uploadedFirst, date)
            val after = listed(timeline.events, date)
            val answers = AppUsageDays.answersOf(date, zone, answered)
            val open = after.flatMap { app -> app.blocks.filter { app.answer(it, answers) == null }.map { app.app to it } }
            val added = after.flatMap { app -> app.blocks.map { app.app to it } } - before.flatMap { app -> app.blocks.map { app.app to it } }.toSet()
            assertEquals(added.toSet(), open.toSet())
            assertEquals(if (open.isEmpty()) AppUsageDayState.ANSWERED else AppUsageDayState.NEW_APPS, AppUsageDays.state(after, answers))
        }
    }

    @Test fun aStudyZoneCorrectionKeepsAnsweredDaysAnswered() {
        val day = LocalDate.of(2026, 6, 15)
        val corrected = ZoneId.of("America/Bogota")
        AppUsageFrequency.values().forEach { frequency ->
            fun listed(studyZone: ZoneId): List<ListedApp> {
                val window = AppUsageDays.bounds(day, studyZone)
                val start = window.start.plus(Duration.ofHours(9))
                val events = listOf(UsageTransition("a.maps", "Maps", start, UsageTransitionKind.FOREGROUND),
                    UsageTransition("a.maps", "Maps", start.plus(Duration.ofHours(2)), UsageTransitionKind.BACKGROUND))
                return AppUsageDays.listing(AppUsageDays.foreground(events, window), emptyMap(), window,
                    AppUsageDays.blocks(day, studyZone, frequency), 60)
            }
            val answered = listed(zone).flatMap { app ->
                app.blocks.map { StoredAnswer(app.app, it.start, day, zone, listOf("Child alone")) }
            }
            val after = listed(corrected)
            val answers = AppUsageDays.answersOf(day, corrected, answered)
            assertEquals(AppUsageDayState.ANSWERED, AppUsageDays.state(after, answers))
            val again = after.flatMap { app ->
                app.blocks.map { AppUsageAnswer(app.app, it.start.atZone(corrected).toOffsetDateTime(), listOf("Child alone")) }
            }
            val conflict = assertThrows(AppUsageDayConflictException::class.java) {
                AppUsageDays.rows(after, answers, again, AppUsageDays.bounds(day, corrected).start)
            }
            assertEquals(AppUsageDayConflict.ALREADY_ANSWERED, conflict.conflict)
        }
    }

    @Test fun anAppIsListedFromOneMinuteOfUse() {
        val day = LocalDate.of(2026, 6, 15)
        val window = AppUsageDays.bounds(day, zone)
        fun listedAfter(seconds: Long): Boolean {
            val start = window.start.plusSeconds(3600)
            val events = listOf(UsageTransition("a.maps", "Maps", start, UsageTransitionKind.FOREGROUND),
                UsageTransition("a.maps", "Maps", start.plusSeconds(seconds), UsageTransitionKind.BACKGROUND))
            return AppUsageDays.listing(AppUsageDays.foreground(events, window), emptyMap(), window,
                AppUsageDays.blocks(day, zone, AppUsageFrequency.DAILY), 60).isNotEmpty()
        }
        assertFalse(listedAfter(59))
        assertTrue(listedAfter(60))
    }
}
