package com.openlattice.chronicle.storage.rls

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.Executors

/**
 * HikariCP connection customizer that clears RLS context when connections
 * are returned to the pool.
 *
 * This ensures that database connections don't leak authorization context
 * between different users. Every time a connection is borrowed from the pool,
 * the application code is responsible for setting the appropriate RLS context
 * before executing queries.
 *
 * Usage:
 * Add to HikariCP configuration (single-line SQL value, shown wrapped here for readability):
 * ```yaml
 * connectionInitSql: "SELECT
 *   set_config('app.current_user_id', '', false),
 *   set_config('app.authorized_studies', '', false),
 *   set_config('app.is_admin', 'false', false)"
 * ```
 *
 * Or programmatically:
 * ```kotlin
 * hikariConfig.connectionInitSql = RLSConnectionCustomizer.CONNECTION_INIT_SQL
 * ```
 *
 * @author uzaira0
 */
public object RLSConnectionCustomizer {

    private val logger = LoggerFactory.getLogger(RLSConnectionCustomizer::class.java)
    private val abortExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "rls-connection-abort").apply { isDaemon = true }
    }

    /**
     * SQL to run when a connection is initialized (borrowed from pool).
     * This ensures a clean RLS context for each new connection use.
     */
    public const val CONNECTION_INIT_SQL: String = """
        SELECT
            set_config('app.current_user_id', '', false),
            set_config('app.authorized_studies', '', false),
            set_config('app.is_admin', 'false', false)
    """

    public const val ADMIN_CONTEXT_SQL: String = """
        SELECT
            set_config('app.current_user_id', 'system', false),
            set_config('app.authorized_studies', '', false),
            set_config('app.is_admin', 'true', false)
    """

    /** Transaction-local variant that cannot leak and must not clear/commit/rollback its caller. */
    public const val ADMIN_TRANSACTION_CONTEXT_SQL: String = """
        SELECT
            set_config('app.current_user_id', 'system', true),
            set_config('app.authorized_studies', '', true),
            set_config('app.is_admin', 'true', true)
    """

    /**
     * Clears the RLS context on a connection.
     * Call this before returning a connection to the pool.
     *
     * @param connection The connection to clear
     */
    // reason: boundary catch — connection.abort may throw any type during cleanup and must be suppressed onto the primary cause
    @Suppress("TooGenericExceptionCaught")
    @JvmStatic
    public fun clearContext(connection: Connection) {
        try {
            if (!connection.autoCommit) {
                connection.rollback()
                connection.autoCommit = true
            }
            connection.createStatement().use { stmt ->
                stmt.execute(CONNECTION_INIT_SQL)
            }
        } catch (e: SQLException) {
            logger.error("Failed to clear RLS context on connection", e)
            try {
                connection.abort(abortExecutor)
            } catch (abortException: Exception) {
                e.addSuppressed(abortException)
            }
            throw RLSContextException("Failed to clear RLS context", e)
        }
    }

    /**
     * Validates that RLS context is properly cleared on a connection.
     * Returns true if the context is empty/default.
     *
     * @param connection The connection to validate
     * @return true if context is cleared, false otherwise
     */
    @JvmStatic
    public fun validateContextCleared(connection: Connection): Boolean {
        return try {
            connection.createStatement().use { stmt ->
                stmt.executeQuery("""
                    SELECT
                        current_setting('app.current_user_id', true) as user_id,
                        current_setting('app.authorized_studies', true) as studies,
                        current_setting('app.is_admin', true) as is_admin
                """).use { rs -> isContextRowCleared(rs) }
            }
        } catch (e: SQLException) {
            logger.warn("Failed to validate RLS context", e)
            false
        }
    }

    private fun isContextRowCleared(rs: java.sql.ResultSet): Boolean {
        if (!rs.next()) {
            return true // No result means default empty context
        }
        val userId = rs.getString("user_id") ?: ""
        val studies = rs.getString("studies") ?: ""
        val isAdmin = rs.getString("is_admin") ?: "false"
        return userId.isEmpty() && studies.isEmpty() && isAdmin != "true"
    }

    @JvmStatic
    public fun <T> withAdminContext(connection: Connection, block: () -> T): T {
        connection.createStatement().use { stmt ->
            stmt.execute(ADMIN_CONTEXT_SQL)
        }

        return try {
            block()
        } finally {
            clearContext(connection)
        }
    }

    /**
     * Transaction-local admin context that is *restored* to the caller's context afterwards.
     *
     * [withAdminTransactionContext] leaves `app.is_admin = true` in place for the rest of the
     * transaction. When a guard evaluates a server-side predicate and then runs the caller's own
     * write in the same transaction, that would silently elevate the write past RLS, so snapshot
     * the four RLS settings and put them back before returning.
     */
    @JvmStatic
    public fun <T> withRestoredAdminTransactionContext(connection: Connection, block: () -> T): T {
        check(!connection.autoCommit) { "Transaction-local RLS admin context requires an active transaction" }
        val saved = RLS_CONTEXT_SETTINGS.map { setting -> setting to readSetting(connection, setting) }
        connection.createStatement().use { statement ->
            statement.execute(ADMIN_TRANSACTION_CONTEXT_SQL)
        }
        return try {
            block()
        } finally {
            connection.prepareStatement("SELECT set_config(?, ?, true)").use { statement ->
                saved.forEach { (setting, value) ->
                    statement.setString(1, setting)
                    statement.setString(2, value)
                    statement.execute()
                }
            }
        }
    }

    private val RLS_CONTEXT_SETTINGS = listOf(
        "app.current_user_id",
        "app.authorized_studies",
        "app.is_admin",
        "app.authorized_orgs",
    )

    private fun readSetting(connection: Connection, setting: String): String =
        connection.prepareStatement("SELECT coalesce(current_setting(?, true), '')").use { statement ->
            statement.setString(1, setting)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getString(1) ?: "" else ""
            }
        }

    @JvmStatic
    public fun <T> withAdminTransactionContext(connection: Connection, block: () -> T): T {
        check(!connection.autoCommit) { "Transaction-local RLS admin context requires an active transaction" }
        connection.createStatement().use { statement ->
            statement.execute(ADMIN_TRANSACTION_CONTEXT_SQL)
        }
        return block()
    }
}
