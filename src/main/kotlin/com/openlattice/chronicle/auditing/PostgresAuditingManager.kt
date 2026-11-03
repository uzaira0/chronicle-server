package com.openlattice.chronicle.auditing

import com.openlattice.chronicle.storage.withTransaction
import com.fasterxml.jackson.core.JsonProcessingException
import com.geekbeast.configuration.postgres.PostgresFlavor
import com.geekbeast.mappers.mappers.ObjectMappers
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.buildMultilineInsertAuditEvents
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.storage.PostgresEventTables.Companion.buildInsertAuditBufferEvents
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import org.slf4j.LoggerFactory

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
/**
 * The one writer of the append-only `audit` table. Successes are staged in their owning transaction; denials
 * and failures are committed independently. Every committed row is also written as one JSON line to the
 * `AUDIT` logger, the SIEM file.
 */
public open class PostgresAuditingManager(storageResolver: StorageResolver) : AuditingManager {
    private companion object {
        private val siem = LoggerFactory.getLogger("AUDIT")
    }

    private val platformStorage = storageResolver.getPlatformStorage()
    override val transactionStorage: com.zaxxer.hikari.HikariDataSource get() = platformStorage
    private val stagedAuditSql = buildInsertAuditBufferEvents()
    private val auditStorage = storageResolver.getAuditStorage()
    private val mapper = ObjectMappers.newJsonMapper()
    private val insertAuditSql = buildMultilineInsertAuditEvents(
        numLines = 1,
        includeOnConflict = auditStorage.flavor == PostgresFlavor.VANILLA,
    )

    override fun recordEvents(events: List<AuditableEvent>): Int {
        if (events.isEmpty()) return 0
        val (failures, successes) = events.partition { AuditObservation.isDenial(it.eventType) }
        val owner = PinnedPlatformConnection.owningConnection(platformStorage)
        if (owner == null) {
            check(successes.isEmpty()) {
                "Mutation success requires its transaction owner"
            }
        }
        val staged = if (owner != null) recordEvents(owner, successes) else 0
        return staged + persistIndependent(if (owner != null) failures else events)
    }

    override fun recordObservations(observation: AuditObservation): Int {
        val owner = PinnedPlatformConnection.owningConnection(platformStorage)
        // A nested helper's commit is deferred by a pin; its success must follow the outer owner.
        if (owner != null && observation is AuditObservation.CommittedEffect) {
            return recordEvents(owner, observation.events)
        }
        return persistIndependent(observation.events)
    }

    private fun persistIndependent(events: List<AuditableEvent>): Int {
        if (events.isEmpty()) return 0
        return auditStorage.dataSource.connection.use { connection ->
            connection.withTransaction {
                val inserted = insert(connection, insertAuditSql, events)
                connection.commit()
                writeSiem(events)
                inserted
            }
        }
    }

    override fun recordEvents(connection: Connection, events: List<AuditableEvent>): Int {
        check(!connection.autoCommit) { "Success audits require an active transaction" }
        if (events.isEmpty()) return 0
        check(PinnedPlatformConnection.owningConnection(platformStorage, connection) != null) {
            "Success audits require their transaction owner"
        }
        // V1 forwards audit_buffer into the append-only audit table in this transaction.
        insert(connection, stagedAuditSql, events)
        PinnedPlatformConnection.afterCommit(platformStorage, Any()) { writeSiem(events) }
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
        ps.setObject(11, event.eventId)
        ps.setString(12, event.request?.clientIpRef)
        ps.setString(13, event.request?.userAgent)
        ps.setString(14, event.request?.path)
        ps.setString(15, event.request?.method)
    }

    /** An unserializable event must not undo its committed row; the loss is logged where operators look. */
    private fun writeSiem(events: List<AuditableEvent>) = events.forEach { event ->
        try {
            siem.info(mapper.writeValueAsString(event))
        } catch (e: JsonProcessingException) {
            LoggerFactory.getLogger(PostgresAuditingManager::class.java)
                .error("Failed to write audit event {} to the SIEM file", event.eventId, e)
        }
    }
}
