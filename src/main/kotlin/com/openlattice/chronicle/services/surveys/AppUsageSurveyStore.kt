package com.openlattice.chronicle.services.surveys

import com.geekbeast.postgres.PostgresArrays
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.APP_USAGE_SURVEY
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.util.SqlFailures
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Reads usage transitions for survey days and reads and writes their answers. */
internal class AppUsageSurveyStore(private val storageResolver: StorageResolver) {
    internal companion object {
        private const val SELECTED = "app_package_name, application_label, event_timestamp, event_type, interaction_type"
        private const val PARTICIPANT = "study_id = ? AND participant_id = ? AND (username IS NULL OR username = '')"
        private const val FOREGROUND = "(event_type = 1 OR interaction_type = 'Move to Foreground')"
        private const val SESSION = "(event_type IN (1, 2) OR interaction_type IN ('Move to Foreground', 'Move to Background'))"
        private const val RESTART = "event_type IN (26, 27)"

        /** Bind order: study, participant, span start, span end. */
        private val IN_SPAN_SQL = """
            SELECT $SELECTED FROM chronicle_usage_events
            WHERE $PARTICIPANT AND event_timestamp >= ? AND event_timestamp < ? AND ($SESSION OR $RESTART)
        """.trimIndent()

        /**
         * Each app's last session event before the span, foreground last among equal instants. Bind order: study,
         * participant, span start.
         * Known limit: scans the participant's whole history before the span; an index-only per-app lookup if it grows.
         */
        private val CARRIED_SQL = """
            SELECT DISTINCT ON (app_package_name) $SELECTED FROM chronicle_usage_events
            WHERE $PARTICIPANT AND event_timestamp < ? AND $SESSION
            ORDER BY app_package_name, event_timestamp DESC, $FOREGROUND DESC
        """.trimIndent()

        /** The last restart before the span. Bind order: study, participant, span start. */
        private val LAST_RESTART_SQL = """
            SELECT $SELECTED FROM chronicle_usage_events
            WHERE $PARTICIPANT AND event_timestamp < ? AND $RESTART
            ORDER BY event_timestamp DESC, event_type DESC LIMIT 1
        """.trimIndent()

        /** Each app's first session event from the span end, background first among equal instants. Bind: study, participant, span end. */
        private val NEXT_SQL = """
            SELECT DISTINCT ON (app_package_name) $SELECTED FROM chronicle_usage_events
            WHERE $PARTICIPANT AND event_timestamp >= ? AND $SESSION
            ORDER BY app_package_name, event_timestamp, $FOREGROUND
        """.trimIndent()

        /** The first restart from the span end. Bind order: study, participant, span end. */
        private val NEXT_RESTART_SQL = """
            SELECT $SELECTED FROM chronicle_usage_events
            WHERE $PARTICIPANT AND event_timestamp >= ? AND $RESTART
            ORDER BY event_timestamp, event_type LIMIT 1
        """.trimIndent()

        private val ANSWERS_SQL = """
            SELECT app_package_name, event_timestamp, survey_date, timezone, users FROM ${APP_USAGE_SURVEY.name}
            WHERE study_id = ? AND participant_id = ?
        """.trimIndent()

        private val INSERT_SQL = """
            INSERT INTO ${APP_USAGE_SURVEY.name}
                (study_id, participant_id, submission_date, application_label, app_package_name, event_timestamp,
                 timezone, users, survey_date)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        private const val UNIQUE_VIOLATION = "23505"

        private fun kind(rows: ResultSet): UsageTransitionKind? = when (rows.getInt("event_type")) {
            1 -> UsageTransitionKind.FOREGROUND
            2 -> UsageTransitionKind.BACKGROUND
            26 -> UsageTransitionKind.SHUTDOWN
            27 -> UsageTransitionKind.STARTUP
            else -> when (rows.getString("interaction_type")) {
                "Move to Foreground" -> UsageTransitionKind.FOREGROUND
                "Move to Background" -> UsageTransitionKind.BACKGROUND
                else -> null
            }
        }
    }

    /** What [AppUsageDays.foreground] needs for [span]: carried-in state, the span's own transitions and what ends them. */
    fun transitions(studyId: UUID, participantId: String, span: OpenEndRange<Instant>, excluded: Collection<String>): List<UsageTransition> {
        val events = storageResolver.resolveAndGetFlavor(studyId).dataSource
        return events.connection.use { connection ->
            val start = span.start.atOffset(ZoneOffset.UTC)
            val end = span.endExclusive.atOffset(ZoneOffset.UTC)
            listOf(IN_SPAN_SQL to listOf(start, end), CARRIED_SQL to listOf(start), LAST_RESTART_SQL to listOf(start),
                NEXT_SQL to listOf(end), NEXT_RESTART_SQL to listOf(end)).flatMap { (sql, bounds) ->
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, studyId.toString())
                    statement.setString(2, participantId)
                    bounds.forEachIndexed { index, bound -> statement.setObject(index + 3, bound) }
                    statement.executeQuery().use { rows ->
                        generateSequence { if (rows.next()) rows else null }.mapNotNull { row ->
                            val kind = kind(row) ?: return@mapNotNull null
                            val app = row.getString("app_package_name") ?: return@mapNotNull null
                            UsageTransition(app, row.getString("application_label"),
                                row.getObject("event_timestamp", OffsetDateTime::class.java).toInstant(), kind)
                        }.toList()
                    }
                }
            }
        }.filter { it.kind == UsageTransitionKind.SHUTDOWN || it.kind == UsageTransitionKind.STARTUP || it.app !in excluded }
    }

    fun answers(connection: Connection, studyId: UUID, participantId: String): List<StoredAnswer> =
        connection.prepareStatement(ANSWERS_SQL).use { statement ->
            statement.setObject(1, studyId)
            statement.setString(2, participantId)
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows else null }.map { row ->
                    StoredAnswer(row.getString("app_package_name"),
                        row.getObject("event_timestamp", OffsetDateTime::class.java).toInstant(),
                        row.getObject("survey_date", LocalDate::class.java),
                        row.getString("timezone")?.let(ZoneId::of),
                        (row.getArray("users")?.array as? Array<*>)?.map { it.toString() }.orEmpty())
                }.toList()
            }
        }

    /** Plain inserts: an answer that already exists is a conflict, never silently kept or replaced. */
    fun insert(
        connection: Connection,
        studyId: UUID,
        participantId: String,
        date: LocalDate,
        zone: ZoneId,
        answeredAt: OffsetDateTime,
        rows: List<AppUsageAnswerRow>,
    ): Boolean = try {
        connection.prepareStatement(INSERT_SQL).use { statement ->
            rows.forEach { row ->
                statement.setObject(1, studyId)
                statement.setString(2, participantId)
                statement.setObject(3, answeredAt)
                statement.setString(4, row.app?.label)
                statement.setString(5, row.app?.app ?: "")
                statement.setObject(6, row.blockStart.atZone(zone).toOffsetDateTime())
                statement.setString(7, zone.id)
                statement.setArray(8, PostgresArrays.createTextArray(connection, row.users))
                statement.setObject(9, date)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        true
    } catch (failure: SQLException) {
        // A batch reports its statement's error as the next exception.
        if (generateSequence(failure) { it.nextException }.any { it.sqlState == UNIQUE_VIOLATION }) false else throw SqlFailures.sanitize(failure)
    }
}
