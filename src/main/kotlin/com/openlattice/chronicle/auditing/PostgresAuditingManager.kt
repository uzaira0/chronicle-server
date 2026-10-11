package com.openlattice.chronicle.auditing

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.buildMultilineInsertAuditEvents
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.buildInsertAuditBufferEvents
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public open class PostgresAuditingManager(storageResolver: StorageResolver) : AuditingManager {
    private val platformStorage = storageResolver.getPlatformStorage()
    private val stagedAuditSql = buildInsertAuditBufferEvents()
    private val auditStorage = storageResolver.getAuditStorage()
    private val mapper = ObjectMappers.newJsonMapper()
    private val insertAuditSql = buildMultilineInsertAuditEvents(
        numLines = 1,
        includeOnConflict = auditStorage.first == PostgresFlavor.VANILLA,
    )

    override fun recordEvents(events: List<AuditableEvent>): Int {
        if (events.isEmpty()) return 0
        val (failures, successes) = events.partition {
            it.eventType == AuditEventType.ACCESS_DENIED || it.eventType == AuditEventType.AUTHORIZATION_CHECK_FAILED
        }
        val owner = PinnedPlatformConnection.owningConnection(platformStorage)
        val staged = if (owner != null) recordEvents(owner, successes) else 0
        val independent = if (owner != null) failures else events
        if (independent.isEmpty()) return staged
        return auditStorage.second.connection.use { connection ->
            connection.autoCommit = false
            try {
                val inserted = insert(connection, insertAuditSql, independent) + staged
                connection.commit()
                inserted
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
    }

    override fun recordEvents(connection: Connection, events: List<AuditableEvent>): Int {
        check(!connection.autoCommit) { "Success audits require an active transaction" }
        if (events.isEmpty()) return 0
        // V59 forwards audit_buffer into the append-only audit table in this transaction.
        insert(connection, stagedAuditSql, events)
        return events.size
    }

    private fun insert(connection: Connection, sql: String, events: List<AuditableEvent>): Int =
        connection.prepareStatement(sql).use { ps ->
            events.forEach { event -> bind(ps, event); ps.addBatch() }
            ps.executeBatch().sumOf { result ->
                when {
                    result >= 0 -> result
                    result == Statement.SUCCESS_NO_INFO -> 1
                    else -> error("Audit insert batch reported failure status $result")
                }
            }
        }

    private fun bind(ps: PreparedStatement, event: AuditableEvent) {
        ps.setString(1, event.aclKey.index)
        ps.setString(2, event.securablePrincipalId.toString())
        ps.setString(3, event.principal.type.name)
        ps.setString(4, event.principal.id)
        ps.setString(5, event.eventType.name)
        ps.setString(6, event.study.toString())
        ps.setString(7, event.organization.toString())
        ps.setString(8, event.description)
        ps.setString(9, mapper.writeValueAsString(event.data))
        ps.setObject(10, event.timestamp)
    }
}
