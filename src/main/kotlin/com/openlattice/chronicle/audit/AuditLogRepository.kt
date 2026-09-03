/*
 * Copyright (C) 2024. Chronicle.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.openlattice.chronicle.audit

import com.fasterxml.jackson.databind.ObjectMapper
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Repository for persisting audit log entries to PostgreSQL.
 * This provides the database storage component of the dual-write audit system.
 */
public open class AuditLogRepository(
    private val storageResolver: StorageResolver,
    private val objectMapper: ObjectMapper
) {
    internal companion object {
        private val logger = LoggerFactory.getLogger(AuditLogRepository::class.java)

        private const val TABLE_NAME = "audit_logs"

        private val INSERT_SQL = """
            INSERT INTO $TABLE_NAME (
                id, timestamp, user_id, user_role, ip_address, user_agent,
                action, resource_type, resource_id, study_id, organization_id,
                success, error_message, accessed_phi, phi_fields,
                request_path, request_method, response_code, duration_ms,
                additional_data
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
        """.trimIndent()

        private val SELECT_BY_ID_SQL = """
            SELECT * FROM $TABLE_NAME WHERE id = ?
        """.trimIndent()

        private val SELECT_BY_USER_SQL = """
            SELECT * FROM $TABLE_NAME
            WHERE user_id = ?
            ORDER BY timestamp DESC
            LIMIT ?
        """.trimIndent()

        private val SELECT_BY_STUDY_SQL = """
            SELECT * FROM $TABLE_NAME
            WHERE study_id = ?
            ORDER BY timestamp DESC
            LIMIT ?
        """.trimIndent()

        private val SELECT_BY_DATE_RANGE_SQL = """
            SELECT * FROM $TABLE_NAME
            WHERE timestamp >= ? AND timestamp <= ?
            ORDER BY timestamp DESC
            LIMIT ?
        """.trimIndent()

        private val SELECT_PHI_ACCESS_SQL = """
            SELECT * FROM $TABLE_NAME
            WHERE accessed_phi = true
            AND timestamp >= ? AND timestamp <= ?
            ORDER BY timestamp DESC
            LIMIT ?
        """.trimIndent()

        private val SELECT_FAILED_OPERATIONS_SQL = """
            SELECT * FROM $TABLE_NAME
            WHERE success = false
            AND timestamp >= ? AND timestamp <= ?
            ORDER BY timestamp DESC
            LIMIT ?
        """.trimIndent()

        private val SELECT_SECURITY_EVENTS_SQL = """
            SELECT * FROM $TABLE_NAME
            WHERE action IN ('LOGIN', 'LOGOUT', 'LOGIN_FAILED', 'UNAUTHORIZED_ACCESS', 'ACCESS_DENIED', 'PERMISSION_CHANGE', 'SUSPICIOUS_ACTIVITY')
            AND timestamp >= ? AND timestamp <= ?
            ORDER BY timestamp DESC
            LIMIT ?
        """.trimIndent()

        private val COUNT_BY_ACTION_SQL = """
            SELECT action, COUNT(*) as count
            FROM $TABLE_NAME
            WHERE timestamp >= ? AND timestamp <= ?
            GROUP BY action
            ORDER BY count DESC
        """.trimIndent()
    }

    /**
     * Saves a single audit log entry to the database.
     * @return true if the save was successful, false otherwise
     */
    // reason: boundary catch — any persistence failure must be swallowed into a false result
    @Suppress("TooGenericExceptionCaught")
    public fun save(entry: AuditLogEntry): Boolean {
        return try {
            storageResolver.getPlatformStorage().connection.use { connection ->
                save(connection, entry)
            }
            true
        } catch (ex: Exception) {
            logger.error("Failed to save audit log entry: ${entry.id}", ex)
            false
        }
    }

    /**
     * Saves a single audit log entry using an existing connection.
     */
    public fun save(connection: Connection, entry: AuditLogEntry) {
        connection.prepareStatement(INSERT_SQL).use { ps ->
            bindEntry(ps, entry)
            ps.executeUpdate()
        }
    }

    /**
     * Batch saves multiple audit log entries.
     * @return the number of entries successfully saved
     */
    public fun saveBatch(entries: List<AuditLogEntry>): Int {
        if (entries.isEmpty()) return 0

        return storageResolver.getPlatformStorage().connection.use { connection ->
            saveBatchTransactional(connection, entries)
        }
    }

    // reason: boundary catch — any failure must trigger rollback before being rethrown
    @Suppress("TooGenericExceptionCaught")
    private fun saveBatchTransactional(connection: Connection, entries: List<AuditLogEntry>): Int {
        connection.autoCommit = false
        return try {
            connection.prepareStatement(INSERT_SQL).use { ps ->
                entries.forEach { entry ->
                    bindEntry(ps, entry)
                    ps.addBatch()
                }
                val results = ps.executeBatch()
                val saved = results.count { result ->
                    result >= 0 || result == Statement.SUCCESS_NO_INFO
                }
                if (saved != entries.size || results.any { it == Statement.EXECUTE_FAILED }) {
                    throw SQLException("Audit batch persisted $saved of ${entries.size} entries")
                }
                connection.commit()
                saved
            }
        } catch (ex: Exception) {
            connection.rollback()
            throw ex
        } finally {
            connection.autoCommit = true
        }
    }

    /**
     * Executes a parameterized query and maps each row into an [AuditLogEntry].
     */
    private fun queryEntries(sql: String, bind: (PreparedStatement) -> Unit): List<AuditLogEntry> {
        return storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(sql).use { ps ->
                bind(ps)
                ps.executeQuery().use { rs -> collectEntries(rs) }
            }
        }
    }

    private fun collectEntries(rs: java.sql.ResultSet): List<AuditLogEntry> {
        val entries = mutableListOf<AuditLogEntry>()
        while (rs.next()) {
            entries.add(mapResultSetToEntry(rs))
        }
        return entries
    }

    /**
     * Retrieves an audit log entry by its ID.
     */
    public fun findById(id: UUID): AuditLogEntry? {
        return queryEntries(SELECT_BY_ID_SQL) { ps ->
            ps.setObject(1, id)
        }.firstOrNull()
    }

    /**
     * Retrieves audit log entries for a specific user.
     */
    public fun findByUserId(userId: UUID, limit: Int = 100): List<AuditLogEntry> {
        return queryEntries(SELECT_BY_USER_SQL) { ps ->
            ps.setObject(1, userId)
            ps.setInt(2, limit)
        }
    }

    /**
     * Retrieves audit log entries for a specific study.
     */
    public fun findByStudyId(studyId: UUID, limit: Int = 100): List<AuditLogEntry> {
        return queryEntries(SELECT_BY_STUDY_SQL) { ps ->
            ps.setObject(1, studyId)
            ps.setInt(2, limit)
        }
    }

    /**
     * Retrieves audit log entries within a date range.
     */
    public fun findByDateRange(start: Instant, end: Instant, limit: Int = 1000): List<AuditLogEntry> {
        return queryEntries(SELECT_BY_DATE_RANGE_SQL) { ps ->
            ps.setTimestamp(1, Timestamp.from(start))
            ps.setTimestamp(2, Timestamp.from(end))
            ps.setInt(3, limit)
        }
    }

    /**
     * Retrieves PHI access events within a date range.
     */
    public fun findPhiAccessEvents(start: Instant, end: Instant, limit: Int = 1000): List<AuditLogEntry> {
        return queryEntries(SELECT_PHI_ACCESS_SQL) { ps ->
            ps.setTimestamp(1, Timestamp.from(start))
            ps.setTimestamp(2, Timestamp.from(end))
            ps.setInt(3, limit)
        }
    }

    /**
     * Retrieves failed operations within a date range.
     */
    public fun findFailedOperations(start: Instant, end: Instant, limit: Int = 1000): List<AuditLogEntry> {
        return queryEntries(SELECT_FAILED_OPERATIONS_SQL) { ps ->
            ps.setTimestamp(1, Timestamp.from(start))
            ps.setTimestamp(2, Timestamp.from(end))
            ps.setInt(3, limit)
        }
    }

    /**
     * Retrieves security events within a date range.
     */
    public fun findSecurityEvents(start: Instant, end: Instant, limit: Int = 1000): List<AuditLogEntry> {
        return queryEntries(SELECT_SECURITY_EVENTS_SQL) { ps ->
            ps.setTimestamp(1, Timestamp.from(start))
            ps.setTimestamp(2, Timestamp.from(end))
            ps.setInt(3, limit)
        }
    }

    /**
     * Gets action counts for reporting purposes.
     */
    public fun getActionCounts(start: Instant, end: Instant): Map<AuditAction, Long> {
        return storageResolver.getPlatformStorage().connection.use { connection ->
            connection.prepareStatement(COUNT_BY_ACTION_SQL).use { ps ->
                ps.setTimestamp(1, Timestamp.from(start))
                ps.setTimestamp(2, Timestamp.from(end))
                ps.executeQuery().use { rs -> collectActionCounts(rs) }
            }
        }
    }

    private fun collectActionCounts(rs: java.sql.ResultSet): Map<AuditAction, Long> {
        val counts = mutableMapOf<AuditAction, Long>()
        while (rs.next()) {
            val actionName = rs.getString("action")
            val action = runCatching { AuditAction.valueOf(actionName) }.getOrNull()
            if (action != null) {
                counts[action] = rs.getLong("count")
            }
        }
        return counts
    }

    private fun bindEntry(ps: PreparedStatement, entry: AuditLogEntry) {
        ps.setObject(1, entry.id)
        ps.setTimestamp(2, Timestamp.from(entry.timestamp))
        ps.setObject(3, entry.userId)
        ps.setString(4, entry.userRole)
        ps.setString(5, entry.ipAddress)
        ps.setString(6, entry.userAgent)
        ps.setString(7, entry.action.name)
        ps.setString(8, entry.resourceType)
        ps.setObject(9, entry.resourceId)
        ps.setObject(10, entry.studyId)
        ps.setObject(11, entry.organizationId)
        ps.setBoolean(12, entry.success)
        ps.setString(13, entry.errorMessage)
        ps.setBoolean(14, entry.accessedPHI)
        ps.setArray(15, entry.phiFields?.let {
            ps.connection.createArrayOf("text", it.toTypedArray())
        })
        ps.setString(16, entry.requestPath)
        ps.setString(17, entry.requestMethod)
        entry.responseCode?.let { ps.setInt(18, it) } ?: ps.setNull(18, java.sql.Types.INTEGER)
        entry.durationMs?.let { ps.setLong(19, it) } ?: ps.setNull(19, java.sql.Types.BIGINT)
        ps.setString(20, entry.additionalData?.let { objectMapper.writeValueAsString(it) })
    }

    private fun mapResultSetToEntry(rs: java.sql.ResultSet): AuditLogEntry {
        val phiFieldsArray = rs.getArray("phi_fields")
        val phiFields = phiFieldsArray?.let {
            @Suppress("UNCHECKED_CAST")
            (it.array as Array<String>).toList()
        }

        val additionalDataJson = rs.getString("additional_data")
        val additionalData = additionalDataJson?.let {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(it, Map::class.java) as Map<String, Any>
        }

        return AuditLogEntry(
            id = rs.getObject("id") as UUID,
            timestamp = rs.getTimestamp("timestamp").toInstant(),
            userId = rs.getObject("user_id") as? UUID,
            userRole = rs.getString("user_role"),
            ipAddress = rs.getString("ip_address"),
            userAgent = rs.getString("user_agent"),
            action = AuditAction.valueOf(rs.getString("action")),
            resourceType = rs.getString("resource_type"),
            resourceId = rs.getObject("resource_id") as? UUID,
            studyId = rs.getObject("study_id") as? UUID,
            organizationId = rs.getObject("organization_id") as? UUID,
            success = rs.getBoolean("success"),
            errorMessage = rs.getString("error_message"),
            accessedPHI = rs.getBoolean("accessed_phi"),
            phiFields = phiFields,
            requestPath = rs.getString("request_path"),
            requestMethod = rs.getString("request_method"),
            responseCode = rs.getInt("response_code").takeIf { !rs.wasNull() },
            durationMs = rs.getLong("duration_ms").takeIf { !rs.wasNull() },
            additionalData = additionalData
        )
    }
}
