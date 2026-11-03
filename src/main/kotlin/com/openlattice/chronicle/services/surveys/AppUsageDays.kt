package com.openlattice.chronicle.services.surveys

import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.settings.AppUsageFrequency
import com.openlattice.chronicle.storage.SurveyEnrollment
import com.openlattice.chronicle.study.StudySettingType
import com.openlattice.chronicle.study.StudySettings
import com.openlattice.chronicle.survey.AppUsageAnswer
import com.openlattice.chronicle.survey.AppUsageDayConflict
import com.openlattice.chronicle.survey.AppUsageDayState
import com.openlattice.chronicle.survey.SurveySettings
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Tie order at one instant: a session closes before the next one opens, so an activity switch keeps both. */
internal enum class UsageTransitionKind { BACKGROUND, SHUTDOWN, STARTUP, FOREGROUND }

internal data class UsageTransition(val app: String, val label: String?, val at: Instant, val kind: UsageTransitionKind)

/** How a study asks about one survey day. */
public data class AppUsageDayRules(val zone: ZoneId, val thresholdSeconds: Int, val frequency: AppUsageFrequency)

internal data class ListedApp(val app: String, val label: String, val blocks: List<OpenEndRange<Instant>>) {
    /** The stored answer to [block] of this app, if there is one. */
    fun answer(block: OpenEndRange<Instant>, answers: List<StoredAnswer>): StoredAnswer? =
        answers.firstOrNull { it.app == app && it.at in block }
}

/**
 * A stored answer row: [at] is its block start in [zone], or the usage event of a row written before survey days
 * existed (no [surveyDate]).
 */
internal data class StoredAnswer(val app: String, val at: Instant, val surveyDate: LocalDate?, val zone: ZoneId?,
                                 val users: List<String>)

internal data class AppUsageAnswerRow(val app: ListedApp?, val blockStart: Instant, val users: List<String>)

/**
 * The one owner of the survey-day rule. A survey day is a calendar day in the study time zone, answerable once it has
 * ended on the server clock; the first day starts at enrollment and the last ends at the stop of collection.
 */
internal object AppUsageDays {
    private val HOUR: Duration = Duration.ofHours(1)

    fun bounds(date: LocalDate, zone: ZoneId): OpenEndRange<Instant> =
        date.atStartOfDay(zone).toInstant()..<date.plusDays(1).atStartOfDay(zone).toInstant()

    /** The enrolled part of [date]; null when the participant was not enrolled on it. */
    fun window(date: LocalDate, zone: ZoneId, enrollment: SurveyEnrollment): OpenEndRange<Instant>? {
        val day = bounds(date, zone)
        val start = maxOf(day.start, enrollment.from ?: return null)
        val end = enrollment.until?.let { minOf(it, day.endExclusive) } ?: day.endExclusive
        if (start >= end || enrollment.closed.any { it.start <= start && end <= it.endExclusive }) return null
        return start..<end
    }

    /** Every ended day with enrolled time, oldest first. */
    fun endedDays(zone: ZoneId, enrollment: SurveyEnrollment, now: Instant): List<LocalDate> =
        generateSequence(enrollment.from?.atZone(zone)?.toLocalDate()) { it.plusDays(1) }
            .takeWhile { date ->
                val day = bounds(date, zone)
                day.endExclusive <= now && (enrollment.until == null || day.start < enrollment.until)
            }
            .filter { window(it, zone, enrollment) != null }
            .toList()

    /**
     * Foreground intervals per app inside [span]. A session counts by the transition that ends it, wherever that falls,
     * so pass each app's last transition before the span and its first one from the span end, with the restarts
     * around them: use across midnight, and a whole day in one app, counts. A shutdown closes every session; a startup
     * without one (power loss) drops them, as nothing tells how long they lasted. A foreground while open re-anchors
     * the session for the same reason. A session with nothing after it yet counts only to the last transition the
     * server has: the phone's later events may simply not be uploaded, so nothing tells it lasted longer.
     * Each of [stops] (a pause, completion, archive or processing block began) ends every session there, as a shutdown
     * does: the phone drops what happens after it, so nothing tells which app was open when collection reopened.
     */
    fun foreground(transitions: List<UsageTransition>, span: OpenEndRange<Instant>,
                   stops: List<Instant> = emptyList()): Map<String, List<OpenEndRange<Instant>>> {
        val lastSeen = transitions.maxOfOrNull { it.at } ?: return emptyMap()
        val ends = stops.map { UsageTransition("", null, it, UsageTransitionKind.SHUTDOWN) }
        val open = HashMap<String, Instant>()
        val intervals = HashMap<String, MutableList<OpenEndRange<Instant>>>()
        fun close(app: String, from: Instant, to: Instant) {
            val start = maxOf(from, span.start)
            val end = minOf(to, span.endExclusive)
            if (start < end) intervals.getOrPut(app) { mutableListOf() }.add(start..<end)
        }
        (transitions + ends).sortedWith(compareBy({ it.at }, { it.kind })).forEach { event ->
            when (event.kind) {
                UsageTransitionKind.FOREGROUND -> open[event.app] = event.at
                UsageTransitionKind.BACKGROUND -> open.remove(event.app)?.let { close(event.app, it, event.at) }
                UsageTransitionKind.SHUTDOWN -> {
                    open.forEach { (app, from) -> close(app, from, event.at) }
                    open.clear()
                }
                UsageTransitionKind.STARTUP -> open.clear()
            }
        }
        open.forEach { (app, from) -> close(app, from, lastSeen) }
        return intervals
    }

    /** DAILY asks once per app and day; HOURLY once per study-zone hour of the day. */
    fun blocks(date: LocalDate, zone: ZoneId, frequency: AppUsageFrequency): List<OpenEndRange<Instant>> {
        val day = bounds(date, zone)
        return when (frequency) {
            AppUsageFrequency.DAILY -> listOf(day)
            AppUsageFrequency.HOURLY -> generateSequence(day.start) { it.plus(HOUR) }.takeWhile { it < day.endExclusive }
                .map { it..<minOf(it.plus(HOUR), day.endExclusive) }.toList()
        }
    }

    /** Apps used for at least the threshold inside [window], with the blocks they were used in, by label. */
    fun listing(
        intervals: Map<String, List<OpenEndRange<Instant>>>,
        labels: Map<String, String>,
        window: OpenEndRange<Instant>,
        blocks: List<OpenEndRange<Instant>>,
        thresholdSeconds: Int,
    ): List<ListedApp> = intervals.mapNotNull { (app, used) ->
        val inside = used.mapNotNull { overlap(it, window) }
        val total = inside.fold(Duration.ZERO) { sum, range -> sum + Duration.between(range.start, range.endExclusive) }
        if (total < Duration.ofSeconds(thresholdSeconds.toLong())) null
        else ListedApp(app, labels[app] ?: app, blocks.filter { block -> inside.any { overlap(it, block) != null } })
    }.sortedWith(compareBy({ it.label.lowercase() }, { it.app }))

    /**
     * Rows stored for [date], each at its block start in [zone]. An answer is to its survey day, app and hour of that
     * day, so it stays answered when the study zone is corrected later. A row written before survey days existed
     * belongs to the day of its usage event.
     */
    fun answersOf(date: LocalDate, zone: ZoneId, stored: List<StoredAnswer>): List<StoredAnswer> {
        val day = bounds(date, zone)
        return stored.mapNotNull { row ->
            val written = row.zone
            when {
                row.surveyDate == null -> row.takeIf { it.at in day }
                row.surveyDate != date -> null
                written == null || written == zone -> row
                else -> row.copy(at = day.start + Duration.between(bounds(date, written).start, row.at).truncatedTo(ChronoUnit.HOURS),
                    zone = zone)
            }
        }
    }

    /**
     * The rows that store [answers] for a day that lists [listed] and holds [stored]. An empty submission confirms a day
     * with no apps as one row at [dayStart]. Answering a block the day no longer lists, or one already answered, is a
     * conflict for the page to show.
     */
    fun rows(listed: List<ListedApp>, stored: List<StoredAnswer>, answers: List<AppUsageAnswer>,
             dayStart: Instant): List<AppUsageAnswerRow> {
        val matched = answers.map { answer ->
            listed.firstOrNull { it.app == answer.appPackageName }?.let { app ->
                app.blocks.firstOrNull { it.start == answer.blockStart.toInstant() }?.let { Triple(app, it, answer.users) }
            }
        }
        val conflict = when {
            answers.isEmpty() && listed.isNotEmpty() -> AppUsageDayConflict.APPS_CHANGED
            answers.isEmpty() && stored.isNotEmpty() -> AppUsageDayConflict.ALREADY_ANSWERED
            matched.any { it == null } -> AppUsageDayConflict.APPS_CHANGED
            matched.filterNotNull().any { (app, block) -> app.answer(block, stored) != null } -> AppUsageDayConflict.ALREADY_ANSWERED
            else -> null
        }
        if (conflict != null) throw AppUsageDayConflictException(conflict)
        return if (answers.isEmpty()) listOf(AppUsageAnswerRow(null, dayStart, emptyList()))
        else matched.filterNotNull().map { (app, block, users) -> AppUsageAnswerRow(app, block.start, users) }
    }

    fun state(listed: List<ListedApp>, answers: List<StoredAnswer>): AppUsageDayState = when {
        answers.isEmpty() -> AppUsageDayState.UNANSWERED
        listed.any { app -> app.blocks.any { app.answer(it, answers) == null } } -> AppUsageDayState.NEW_APPS
        else -> AppUsageDayState.ANSWERED
    }

    private fun overlap(first: OpenEndRange<Instant>, second: OpenEndRange<Instant>): OpenEndRange<Instant>? {
        val start = maxOf(first.start, second.start)
        val end = minOf(first.endExclusive, second.endExclusive)
        return if (start < end) start..<end else null
    }
}

/** The study settings that shape its survey days. */
public fun appUsageDayRules(settings: StudySettings): AppUsageDayRules = AppUsageDayRules(
    settings.timeZone(),
    (settings[StudySettingType.Survey] as? SurveySettings ?: SurveySettings()).appUsageThresholdInSeconds,
    settings.appUsageSurvey().appUsageFrequency,
)

/** A survey-day submission the page must show again; [conflict] tells it why. */
public class AppUsageDayConflictException(public val conflict: AppUsageDayConflict) :
    ResponseStatusException(HttpStatus.CONFLICT, Messages.get(when (conflict) {
        AppUsageDayConflict.ALREADY_ANSWERED -> "error.appUsage.alreadyAnswered"
        AppUsageDayConflict.APPS_CHANGED -> "error.appUsage.appsChanged"
        AppUsageDayConflict.DAY_UNAVAILABLE -> "error.appUsage.dayUnavailable"
    }))
