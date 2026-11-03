package com.openlattice.chronicle.storage

import java.sql.Connection

/** Kotlin's use keeps a cleanup failure suppressed on the operation that failed first. */
internal fun <T> withFailureCleanup(cleanup: () -> Unit, block: () -> T): T =
    AutoCloseable { cleanup() }.use { block() }

/** The caller commits explicitly; read scopes can instead roll back on success. Nested borrowers retain their owner. */
internal fun <T> Connection.withTransaction(
    ownsTransaction: Boolean = true,
    rollbackOnSuccess: Boolean = false,
    block: () -> T,
): T {
    if (!ownsTransaction) return block()
    val previousAutoCommit = autoCommit
    var canRestore = true
    return withFailureCleanup({ if (canRestore && !isClosed) autoCommit = previousAutoCommit }) {
        autoCommit = false
        runCatching {
            block().also { if (rollbackOnSuccess) rollback() }
        }.onFailure { failure ->
            runCatching { rollback() }.onFailure { rollbackFailure ->
                // Enabling autocommit after an unsuccessful rollback could commit work that was meant to be discarded.
                canRestore = false
                failure.addSuppressed(rollbackFailure)
            }
        }.getOrThrow()
    }
}
