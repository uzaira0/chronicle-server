package com.openlattice.chronicle.auditing

import com.openlattice.chronicle.storage.withTransaction
import java.sql.Connection

/** Business writes and success audits share the caller's transaction. */
public open class AuditedOperation<R>(
    public val connection: Connection,
    public val op: (Connection) -> R,
    public val auditOp: (R) -> List<AuditableEvent>,
    override val auditingManager: AuditingManager
) : AuditingComponent {

    public fun execute(): R {
        return connection.withTransaction {
            val operation = {
                op(connection).also { auditingManager.recordEvents(connection, auditOp(it)) }
            }
            val storage = auditingManager.transactionStorage
            if (storage == null) operation().also { connection.commit() }
            else com.openlattice.chronicle.storage.PinnedPlatformConnection.committing(storage, connection, block = operation)
        }
    }
}

public open class AuditedTransactionBuilder<R>(
    public val connection: Connection,
    override val auditingManager: AuditingManager
) : AuditingComponent {
    private lateinit var op: (Connection) -> R
    private lateinit var auditOp: (R) -> List<AuditableEvent>

    public fun transaction(op: (Connection) -> R): AuditedTransactionBuilder<R> {
        this.op = op
        return this
    }

    public fun audit(auditOp: (R) -> List<AuditableEvent>): AuditedTransactionBuilder<R> {
        this.auditOp = auditOp
        return this
    }

    public fun buildAndRun(): R = build().execute()

    public fun build(): AuditedOperation<R> {
        check(this::op.isInitialized) { "Operation must be initialized." }
        check(this::auditOp.isInitialized) { "Audit operation must be initialized." }
        return AuditedOperation(connection, op, auditOp, auditingManager)
    }
}
