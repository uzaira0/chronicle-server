package com.openlattice.chronicle.util

import com.geekbeast.jdbc.SqlFailures as JdbcSqlFailures
import java.sql.SQLException

/** Safe retry metadata; bound statements, server details and SQL cause chains stay private. */
public typealias ChronicleStorageException = JdbcSqlFailures.StorageFailure

public object SqlFailures {
    public fun sanitize(failure: SQLException): ChronicleStorageException =
        JdbcSqlFailures.sanitize(failure)
}

/** Preserve database error classification after its sensitive cause has been dropped. */
internal fun Throwable.sqlCause(): SQLException? =
    generateSequence(this) { it.cause }.take(32).mapNotNull {
        when (it) {
            is SQLException -> it
            is ChronicleStorageException -> SQLException(it.message, it.sqlState)
            else -> null
        }
    }.firstOrNull()
