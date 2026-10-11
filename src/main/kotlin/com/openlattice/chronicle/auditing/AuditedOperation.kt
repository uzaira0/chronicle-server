package com.openlattice.chronicle.auditing

import java.sql.Connection

/** Business writes and success audits share the caller's transaction. */
public open class AuditedOperation<R>(
    public val connection: Connection,
    public val op: (Connection) -> R,
    public val auditOp: (R) -> List<AuditableEvent>,
    override val auditingManager: AuditingManager
) : AuditingComponent {

    // reason: boundary catch — execute() must roll back and rethrow any failure type from op/audit/commit
    @Suppress("TooGenericExceptionCaught")
    public fun execute(): R {
        val previousAutoCommit = connection.autoCommit
        val result: R
        try {
            connection.autoCommit = false
            result = op(connection)
            auditingManager.recordEvents(connection, auditOp(result))
            connection.commit()
        } catch (ex: Exception) {
            runCatching { connection.rollback() }.exceptionOrNull()?.let(ex::addSuppressed)
            throw ex
        } finally {
            connection.autoCommit = previousAutoCommit
        }

        return result
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
