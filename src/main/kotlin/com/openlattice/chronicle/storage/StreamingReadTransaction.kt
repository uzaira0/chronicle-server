package com.openlattice.chronicle.storage

import com.geekbeast.postgres.streams.StatementHolderSupplier
import com.geekbeast.postgres.streams.PostgresIterator
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.ResultSet

/** Cursor consumers retain the supplier's statement bounds and the iterator's inactivity watchdog. */
internal class StreamingReadTransaction(
    hds: HikariDataSource,
    query: String,
    fetchSize: Int,
    longRunningQueryLimit: Long = 0,
    private val studyScope: StudyReadScope? = null,
    private val bind: ((PreparedStatement) -> Unit)? = null,
) : StatementHolderSupplier(hds, query, fetchSize, false, longRunningQueryLimit) {
    init { require(fetchSize > 0) { "Streaming reads require a transaction and a positive fetch size" } }

    // reason: boundary cleanup — every binder failure must close its partially initialized statement
    @Suppress("TooGenericExceptionCaught")
    override fun buildStatement(connection: Connection): Statement {
        configure(connection)
        val binder = bind ?: return super.buildStatement(connection)
        val statement = connection.prepareStatement(sql)
        try {
            binder(statement)
            return statement
        } catch (failure: Throwable) {
            runCatching { statement.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    override fun execute(statement: Statement): ResultSet {
        val checkpoint = studyScope?.let { StudyDisclosureGeneration(statement.connection).checkpoint(it, hds) }
        val rows = if (statement is PreparedStatement) statement.executeQuery() else super.execute(statement)
        return if (checkpoint == null) rows else GenerationCheckedResultSet(rows, checkpoint, fetchSize)
    }

    private class GenerationCheckedResultSet(
        private val rows: ResultSet,
        private val checkpoint: StudyDisclosureGeneration.Checkpoint,
        private val fetchSize: Int,
    ) : ResultSet by rows {
        private var consumed = 0
        override fun next(): Boolean {
            if (consumed % fetchSize == 0) checkpoint.requireCurrent()
            consumed = (consumed + 1) % fetchSize
            return rows.next()
        }
    }

    companion object {
        internal const val PARALLEL_WORKERS_SETTING = "max_parallel_workers_per_gather"

        fun configure(connection: Connection) {
            check(!connection.autoCommit) { "Streaming reads require an active transaction" }
            connection.createStatement().use {
                it.execute("SET LOCAL statement_timeout = 0")
                it.execute("SET LOCAL idle_in_transaction_session_timeout = '${PostgresIterator.DEFAULT_TIMEOUT_MILLIS}ms'")
                it.execute("SET LOCAL $PARALLEL_WORKERS_SETTING = 0")
            }
        }

        fun configure(statement: PreparedStatement, fetchSize: Int) {
            require(fetchSize > 0) { "Cursor fetch size must be positive" }
            configure(statement.connection)
            statement.fetchSize = fetchSize
        }
    }
}
