package com.openlattice.chronicle.services.dashboard

import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.dashboard.StudyEvent
import com.openlattice.chronicle.dashboard.StudyRealtimeStats
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*

public open class DashboardService(
    private val storageResolver: StorageResolver
) {
    internal companion object {
        private val logger = LoggerFactory.getLogger(DashboardService::class.java)
        private val mapper = ObjectMappers.newJsonMapper()

        private const val GET_STATS_SQL = """
            SELECT study_id, active_participants_24h, data_submissions_24h,
                   total_participants, last_data_received, submissions_by_type, updated_at
            FROM study_realtime_stats
            WHERE study_id = ?
        """

        private const val GET_EVENTS_SQL = """
            SELECT event_id, study_id, event_type, participant_id, metadata, created_at
            FROM study_event_stream
            WHERE study_id = ? AND created_at > ?
            ORDER BY created_at DESC
            LIMIT ?
        """

        private const val INSERT_EVENT_SQL = """
            INSERT INTO study_event_stream (event_id, study_id, event_type, participant_id, metadata)
            VALUES (?, ?, ?, ?, ?::jsonb)
        """

        private const val REFRESH_STATS_SQL = """
            INSERT INTO study_realtime_stats
                (study_id, active_participants_24h, data_submissions_24h, total_participants, last_data_received, submissions_by_type, updated_at)
            SELECT
                ?,
                (SELECT COUNT(DISTINCT participant_id) FROM study_event_stream WHERE study_id = ? AND created_at > now() - interval '24 hours'),
                (SELECT COUNT(*) FROM study_event_stream WHERE study_id = ? AND created_at > now() - interval '24 hours'),
                (SELECT COUNT(*) FROM study_participants WHERE study_id = ?),
                (SELECT MAX(created_at) FROM study_event_stream WHERE study_id = ?),
                '{}'::jsonb,
                now()
            ON CONFLICT (study_id) DO UPDATE
                SET active_participants_24h = EXCLUDED.active_participants_24h,
                    data_submissions_24h = EXCLUDED.data_submissions_24h,
                    total_participants = EXCLUDED.total_participants,
                    last_data_received = EXCLUDED.last_data_received,
                    updated_at = now()
        """
    }

    public fun getStats(studyId: UUID): StudyRealtimeStats {
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(GET_STATS_SQL).use { ps ->
                ps.setObject(1, studyId)
                val rs = ps.executeQuery()
                return if (rs.next()) mapStatsRow(rs) else StudyRealtimeStats(studyId = studyId)
            }
        }
    }

    private fun mapStatsRow(rs: java.sql.ResultSet): StudyRealtimeStats {
        val submissionsByTypeJson = rs.getString("submissions_by_type")
        val submissionsByType: Map<String, Long> = parseSubmissionsByType(submissionsByTypeJson)
        return StudyRealtimeStats(
            studyId = rs.getObject("study_id", UUID::class.java),
            activeParticipants24h = rs.getInt("active_participants_24h"),
            dataSubmissions24h = rs.getLong("data_submissions_24h"),
            totalParticipants = rs.getInt("total_participants"),
            lastDataReceived = rs.getObject("last_data_received", OffsetDateTime::class.java),
            submissionsByType = submissionsByType,
            timestamp = rs.getObject("updated_at", OffsetDateTime::class.java)
        )
    }

    // reason: boundary catch — malformed persisted JSON must fall back to empty without leaking any deserializer type
    @Suppress("TooGenericExceptionCaught")
    private fun parseSubmissionsByType(json: String?): Map<String, Long> {
        return try {
            mapper.readValue(json, mapper.typeFactory.constructMapType(
                Map::class.java, String::class.java, Long::class.javaObjectType
            ))
        } catch (e: Exception) {
            logger.debug("Failed to parse submissions_by_type JSON: {}", e.message)
            emptyMap()
        }
    }

    public fun getRecentEvents(studyId: UUID, limit: Int, since: OffsetDateTime?): List<StudyEvent> {
        val sinceDate = since ?: OffsetDateTime.now(ZoneOffset.UTC).minusHours(24)
        val events = mutableListOf<StudyEvent>()
        storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(GET_EVENTS_SQL).use { ps ->
                ps.setObject(1, studyId)
                ps.setObject(2, sinceDate)
                ps.setInt(3, limit.coerceIn(1, 1000))
                val rs = ps.executeQuery()
                while (rs.next()) {
                    events.add(mapEventRow(rs))
                }
            }
        }
        return events
    }

    private fun mapEventRow(rs: java.sql.ResultSet): StudyEvent {
        val metadataJson = rs.getString("metadata")
        val metadata: Map<String, Any> = parseMetadata(metadataJson)
        return StudyEvent(
            eventId = rs.getObject("event_id", UUID::class.java),
            studyId = rs.getObject("study_id", UUID::class.java),
            eventType = rs.getString("event_type"),
            participantId = rs.getString("participant_id"),
            metadata = metadata,
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java)
        )
    }

    // reason: boundary catch — malformed persisted JSON must fall back to empty without leaking any deserializer type
    @Suppress("TooGenericExceptionCaught")
    private fun parseMetadata(json: String?): Map<String, Any> {
        return try {
            mapper.readValue(json, mapper.typeFactory.constructMapType(
                Map::class.java, String::class.java, Any::class.java
            ))
        } catch (e: Exception) {
            logger.debug("Failed to parse event metadata JSON: {}", e.message)
            emptyMap()
        }
    }

    // reason: boundary catch — dashboard event publish is best-effort; any failure is logged and must not propagate
    @Suppress("TooGenericExceptionCaught")
    public fun publishEvent(studyId: UUID, eventType: String, participantId: String?, metadata: Map<String, Any>) {
        try {
            storageResolver.getPlatformStorage().connection.use { connection ->
                connection.prepareStatement(INSERT_EVENT_SQL).use { ps ->
                    ps.setObject(1, UUID.randomUUID())
                    ps.setObject(2, studyId)
                    ps.setString(3, eventType)
                    ps.setString(4, participantId)
                    ps.setString(5, mapper.writeValueAsString(metadata))
                    ps.executeUpdate()
                }
            }
        } catch (ex: Exception) {
            logger.warn("Failed to publish dashboard event for study {}: {}", studyId, ex.message)
        }
    }

    // reason: boundary catch — stats refresh is best-effort; any failure is logged and must not propagate
    @Suppress("TooGenericExceptionCaught")
    public fun refreshStats(studyId: UUID) {
        try {
            storageResolver.getPlatformStorage().connection.use { connection ->
                connection.prepareStatement(REFRESH_STATS_SQL).use { ps ->
                    ps.setObject(1, studyId)
                    ps.setObject(2, studyId)
                    ps.setObject(3, studyId)
                    ps.setObject(4, studyId)
                    ps.setObject(5, studyId)
                    ps.executeUpdate()
                }
            }
        } catch (ex: Exception) {
            logger.warn("Failed to refresh stats for study {}: {}", studyId, ex.message)
        }
    }
}
