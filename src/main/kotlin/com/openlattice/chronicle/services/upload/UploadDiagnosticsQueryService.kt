package com.openlattice.chronicle.services.upload

import com.fasterxml.jackson.databind.JsonNode
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.study.AndroidDiagnosticCodeSummary
import com.openlattice.chronicle.study.AndroidDiagnosticsHistoryRow
import com.openlattice.chronicle.study.AndroidDiagnosticsPage
import com.openlattice.chronicle.study.DataQualityAlertHistoryItem
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

/** Study-scoped, keyset-paginated full history for Android diagnostics and quality alerts. */
public class UploadDiagnosticsQueryService(
    private val storageResolver: StorageResolver,
) {
    private data class Cursor(
        val day: LocalDate,
        val participantId: String,
        val sortDeviceId: String,
        val rowKind: Int,
        val tieBreaker: String,
    )

    private companion object {
        private const val MAX_LIMIT = 200
        private val mapper = ObjectMappers.newJsonMapper()
    }

    public fun getPage(
        studyId: UUID,
        participantId: String? = null,
        deviceId: UUID? = null,
        fromDay: LocalDate? = null,
        toDay: LocalDate? = null,
        moduleFamily: String? = null,
        issueCode: String? = null,
        cursor: String? = null,
        limit: Int = 50,
    ): AndroidDiagnosticsPage {
        require(limit in 1..MAX_LIMIT) { "Diagnostics page limit must be between 1 and $MAX_LIMIT" }
        require(fromDay == null || toDay == null || !fromDay.isAfter(toDay)) {
            "Diagnostics from day must not be after to day"
        }
        require(cursor == null || cursor.length <= 1024) { "Diagnostics cursor must be at most 1024 characters" }
        val after = cursor?.let(::decodeCursor)
        val diagnosticFilters = mutableListOf("study_id = ?")
        val diagnosticParameters = mutableListOf<Any>(studyId)
        participantId?.let {
            diagnosticFilters += "participant_id = ?"
            diagnosticParameters += it
        }
        deviceId?.let {
            diagnosticFilters += "device_id = ?"
            diagnosticParameters += it
        }
        fromDay?.let {
            diagnosticFilters += "diagnostic_day >= ?"
            diagnosticParameters += it
        }
        toDay?.let {
            diagnosticFilters += "diagnostic_day <= ?"
            diagnosticParameters += it
        }
        moduleFamily?.let {
            diagnosticFilters += "module_family = ?"
            diagnosticParameters += it
        }
        issueCode?.let {
            diagnosticFilters += "issue_code = ?"
            diagnosticParameters += it
        }

        val alertFilters = mutableListOf("study_id = ?")
        val alertParameters = mutableListOf<Any>(studyId)
        participantId?.let {
            alertFilters += "participant_id = ?"
            alertParameters += it
        }
        fromDay?.let {
            alertFilters += "created_at >= (?::date::timestamp AT TIME ZONE 'UTC')"
            alertParameters += it
        }
        toDay?.let {
            alertFilters += "created_at < ((?::date + 1)::timestamp AT TIME ZONE 'UTC')"
            alertParameters += it
        }
        if (deviceId != null || moduleFamily != null) {
            alertFilters += "FALSE"
        }
        issueCode?.let {
            alertFilters += "alert_type = ?"
            alertParameters += it
        }

        val cursorFilter =
            if (after == null) {
                ""
            } else {
                """
                WHERE diagnostic_day < ? OR (diagnostic_day = ? AND (
                    participant_id > ? OR (participant_id = ? AND (
                        sort_device_id > ? OR (sort_device_id = ? AND (
                            row_kind > ? OR (row_kind = ? AND tie_breaker > ?)
                        ))
                    ))
                ))
                """.trimIndent()
            }
        val sql =
            """
            WITH diagnostic_keys AS (
                SELECT participant_id, device_id, diagnostic_day,
                       NULL::uuid AS alert_id, 0::integer AS row_kind,
                       device_id::text AS sort_device_id, ''::text AS tie_breaker
                FROM upload_diagnostics
                WHERE ${diagnosticFilters.joinToString(" AND ")}
                GROUP BY participant_id, device_id, diagnostic_day
            ), alert_keys AS (
                SELECT participant_id, NULL::uuid AS device_id,
                       (created_at AT TIME ZONE 'UTC')::date AS diagnostic_day,
                       alert_id, 1::integer AS row_kind,
                       ''::text AS sort_device_id, alert_id::text AS tie_breaker
                FROM data_quality_alerts
                WHERE ${alertFilters.joinToString(" AND ")}
            ), all_keys AS (
                SELECT * FROM diagnostic_keys
                UNION ALL
                SELECT * FROM alert_keys
            ), page_keys AS (
                SELECT * FROM all_keys
                $cursorFilter
                ORDER BY diagnostic_day DESC, participant_id ASC, sort_device_id ASC,
                         row_kind ASC, tie_breaker ASC
                LIMIT ?
            )
            SELECT page_keys.participant_id, page_keys.device_id, page_keys.diagnostic_day,
                   COALESCE(codes.codes_json, '[]'::jsonb) AS codes_json,
                   alerts.alert_id, alerts.alert_type, alerts.score, alerts.created_at,
                   page_keys.row_kind, page_keys.sort_device_id, page_keys.tie_breaker
            FROM page_keys
            LEFT JOIN LATERAL (
                SELECT jsonb_agg(jsonb_build_object(
                           'eventId', event_id,
                           'moduleFamily', module_family,
                           'issueCode', issue_code,
                           'occurrenceCount', occurrence_count,
                           'firstOccurredAt', to_char(first_occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US') || '+00:00',
                           'lastOccurredAt', to_char(last_occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US') || '+00:00',
                           'httpStatus', http_status,
                           'errorType', error_type
                       ) ORDER BY module_family, issue_code, event_id) AS codes_json
                FROM upload_diagnostics AS diagnostic
                WHERE page_keys.row_kind = 0
                  AND diagnostic.study_id = ?
                  AND diagnostic.participant_id = page_keys.participant_id
                  AND diagnostic.device_id = page_keys.device_id
                  AND diagnostic.diagnostic_day = page_keys.diagnostic_day
                  ${if (moduleFamily != null) "AND diagnostic.module_family = ?" else ""}
                  ${if (issueCode != null) "AND diagnostic.issue_code = ?" else ""}
            ) AS codes ON true
            LEFT JOIN data_quality_alerts AS alerts
              ON page_keys.row_kind = 1 AND alerts.study_id = ? AND alerts.alert_id = page_keys.alert_id
            ORDER BY page_keys.diagnostic_day DESC, page_keys.participant_id ASC, page_keys.sort_device_id ASC,
                     row_kind ASC, tie_breaker ASC
            """.trimIndent()

        val rows =
            storageResolver.getPlatformReadStorage().connection.use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    statement.queryTimeout = 30
                    var index = 1
                    (diagnosticParameters + alertParameters).forEach { value ->
                        when (value) {
                            is UUID -> statement.setObject(index++, value)
                            is LocalDate -> statement.setObject(index++, value)
                            else -> statement.setString(index++, value.toString())
                        }
                    }
                    if (after != null) {
                        statement.setObject(index++, after.day)
                        statement.setObject(index++, after.day)
                        statement.setString(index++, after.participantId)
                        statement.setString(index++, after.participantId)
                        statement.setString(index++, after.sortDeviceId)
                        statement.setString(index++, after.sortDeviceId)
                        statement.setInt(index++, after.rowKind)
                        statement.setInt(index++, after.rowKind)
                        statement.setString(index++, after.tieBreaker)
                    }
                    statement.setInt(index++, limit + 1)
                    statement.setObject(index++, studyId)
                    if (moduleFamily != null) statement.setString(index++, moduleFamily)
                    if (issueCode != null) statement.setString(index++, issueCode)
                    statement.setObject(index, studyId)
                    statement.executeQuery().use { resultSet ->
                        buildList {
                            while (resultSet.next()) {
                                val rowKind = resultSet.getInt("row_kind")
                                val rowDay = resultSet.getObject("diagnostic_day", LocalDate::class.java)
                                val rowParticipant = resultSet.getString("participant_id")
                                val rowDevice = resultSet.getObject("device_id", UUID::class.java)
                                val codes = if (rowKind == 0) parseCodes(resultSet.getString("codes_json")) else emptyList()
                                val alertId = resultSet.getObject("alert_id", UUID::class.java)
                                val alert =
                                    if (alertId == null) {
                                        null
                                    } else {
                                        DataQualityAlertHistoryItem(
                                            alertId = alertId,
                                            alertType = resultSet.getString("alert_type"),
                                            score = resultSet.getDouble("score"),
                                            createdAt = resultSet.getObject("created_at", OffsetDateTime::class.java),
                                        )
                                    }
                                add(
                                    AndroidDiagnosticsHistoryRow(
                                        participantId = rowParticipant,
                                        deviceId = rowDevice,
                                        day = rowDay,
                                        codes = codes,
                                        dataQualityAlert = alert,
                                    ) to
                                        Cursor(
                                            day = rowDay,
                                            participantId = rowParticipant,
                                            sortDeviceId = resultSet.getString("sort_device_id"),
                                            rowKind = rowKind,
                                            tieBreaker = resultSet.getString("tie_breaker"),
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        val hasMore = rows.size > limit
        val pageRows = rows.take(limit)
        return AndroidDiagnosticsPage(
            items = pageRows.map { it.first },
            nextCursor = if (hasMore) pageRows.lastOrNull()?.second?.let(::encodeCursor) else null,
        )
    }

    private fun parseCodes(json: String): List<AndroidDiagnosticCodeSummary> =
        mapper.readTree(json).map { node: JsonNode ->
            AndroidDiagnosticCodeSummary(
                eventId = node.required("eventId").asText(),
                moduleFamily = node.required("moduleFamily").asText(),
                issueCode = node.required("issueCode").asText(),
                occurrenceCount = node.required("occurrenceCount").asLong(),
                firstOccurredAt = OffsetDateTime.parse(node.required("firstOccurredAt").asText()),
                lastOccurredAt = OffsetDateTime.parse(node.required("lastOccurredAt").asText()),
                httpStatus = node.get("httpStatus")?.let { if (it.isNull) null else it.asInt() },
                errorType = node.get("errorType")?.let { if (it.isNull) null else it.asText() },
            )
        }

    private fun encodeCursor(cursor: Cursor): String {
        fun encoded(value: String): String =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
                .ifEmpty { "_" }
        return listOf(
            cursor.day.toString(),
            encoded(cursor.participantId),
            cursor.sortDeviceId,
            cursor.rowKind.toString(),
            encoded(cursor.tieBreaker),
        ).joinToString("~")
    }

    private fun decodeCursor(value: String): Cursor {
        val parts = value.split('~')
        require(parts.size == 5) { "Invalid diagnostics cursor" }

        fun decoded(part: String): String =
            if (part == "_") {
                ""
            } else {
                String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8)
            }
        val rowKind = parts[3].toInt()
        require(rowKind in 0..1) { "Invalid diagnostics cursor row kind" }
        val day = try {
            LocalDate.parse(parts[0])
        } catch (error: DateTimeParseException) {
            throw IllegalArgumentException("Invalid diagnostics cursor date", error)
        }
        return Cursor(
            day = day,
            participantId = decoded(parts[1]),
            sortDeviceId = parts[2],
            rowKind = rowKind,
            tieBreaker = decoded(parts[4]),
        )
    }
}
