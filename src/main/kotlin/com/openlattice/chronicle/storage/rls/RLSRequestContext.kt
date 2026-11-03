package com.openlattice.chronicle.storage.rls

import com.openlattice.chronicle.storage.withFailureCleanup
import com.zaxxer.hikari.HikariDataSource
import com.openlattice.chronicle.storage.PinnedPlatformConnection
import com.openlattice.chronicle.util.SqlFailures
import com.openlattice.chronicle.util.sqlCause
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Receipt ownership distinguishes user subjects from API-key credentials with equal raw ids. */
public enum class ReceiptOwnerKind { USER, API_KEY }

public data class RLSConnectionContext(
    val principalId: String,
    val authorizedStudyIds: Set<UUID>,
    val isAdmin: Boolean,
    val authorizedOrganizationIds: Set<UUID> = emptySet(),
    val isDeletionWorker: Boolean = false,
    val receiptOwnerKind: ReceiptOwnerKind? = null,
) {
    public val authorizedOrganizationsCsv: String = authorizedOrganizationIds.joinToString(",") { it.toString() }
    public val authorizedStudiesCsv: String = authorizedStudyIds.joinToString(",") { it.toString() }
}

public object RLSRequestContext {
    private val currentContext = ThreadLocal<RLSConnectionContext?>()
    internal val anonymousContext = RLSConnectionContext(
        principalId = "",
        authorizedStudyIds = emptySet(),
        isAdmin = false,
    )
    internal val systemContext = RLSConnectionContext(
        principalId = "chronicle-background",
        authorizedStudyIds = emptySet(),
        isAdmin = true,
    )
    private val deletionWorkerContext = RLSConnectionContext(
        principalId = RLSConnectionCustomizer.DELETION_WORKER_ID,
        authorizedStudyIds = emptySet(),
        isAdmin = true,
        isDeletionWorker = true,
    )

    public fun current(): RLSConnectionContext? = currentContext.get()

    public fun set(context: RLSConnectionContext) {
        currentContext.set(context)
    }

    public fun clear() {
        currentContext.remove()
    }

    /**
     * Runs trusted internal work with an explicit database system context.
     *
     * Background executors do not inherit servlet ThreadLocals. Without this boundary a pool
     * authenticated as the restricted application role sees no RLS-protected rows, so scheduled
     * movers silently report an empty queue. Restore any caller context rather than unconditionally
     * clearing it so nested internal work cannot erase an enclosing request context.
     */
    public fun <T> withSystemContext(block: () -> T): T {
        return withContext(systemContext, block)
    }

    /**
     * Runs the erasure worker with its own narrowly recognizable RLS identity.
     *
     * Quarantined participant rows stay hidden from ordinary administrators and
     * background tasks; only this identity can observe them for deletion and
     * residual verification.
     */
    public fun <T> withDeletionWorkerContext(block: () -> T): T {
        return withContext(deletionWorkerContext, block)
    }

    public fun <T> withContext(context: RLSConnectionContext, block: () -> T): T {
        val previous = currentContext.get()
        currentContext.set(context)
        return try {
            block()
        } finally {
            if (previous == null) {
                currentContext.remove()
            } else {
                currentContext.set(previous)
            }
        }
    }
}

public object RLSDataSources {
    private val wrappers = Collections.synchronizedMap(IdentityHashMap<HikariDataSource, HikariDataSource>())
    private val systemWrappers = Collections.synchronizedMap(IdentityHashMap<HikariDataSource, HikariDataSource>())
    private val ROLE_IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /**
     * Non-superuser Postgres role applied via `SET ROLE` before request queries,
     * restoring the pool's authenticated role (`RESET ROLE`) on return. Runtime pools
     * authenticate with a restricted login validated at startup; schema bootstrap and
     * upgrades use a separate owner pool. RESET ROLE cannot regain owner privileges.
     *
     * Every borrow drops privileges, including anonymous requests. Bootstrap and
     * upgrades use their raw datasource; trusted workers install an explicit system context.
     *
     * Creation flows authorize their new study/organization ID inside the creating transaction.
     * Candidate INSERT has its registration policy; linked-candidate writes remain study scoped.
     * Set to `null` when the pool already authenticates as the restricted role.
     * Validated as a SQL identifier.
     */
    @Volatile
    @set:SuppressFBWarnings(
        value = ["ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD"],
        justification = "appRole is global, set-once startup CONFIG — the fixed name of the " +
            "non-superuser Postgres role every request drops to via SET ROLE — NOT per-request " +
            "state, so there is no cross-request leak (the value is identical for all requests). " +
            "It is @Volatile for cross-thread visibility and validated as a SQL identifier. " +
            "Per-request state lives separately in RLSRequestContext.currentContext (a ThreadLocal). " +
            "The setter is a static write only because Kotlin compiles an `object` singleton's " +
            "properties to static fields; findbugs flags the structural pattern, not a real bug.",
    )
    public var appRole: String? = "chronicle_app"
        set(value) {
            require(value == null || ROLE_IDENTIFIER.matches(value)) { "Invalid app role identifier: $value" }
            field = value
        }

    public fun wrapIfRequestScoped(hds: HikariDataSource): HikariDataSource {
        return wrappers.getOrPut(hds) { wrapUncachedRequestScoped(hds) }
    }

    /** Transient session-sharing sources must not remain in the process-wide pool-wrapper cache. */
    internal fun wrapUncachedRequestScoped(hds: HikariDataSource): HikariDataSource =
        RLSAwareHikariDataSource(hds, RLSRequestContext::current)

    /** Explicit bootstrap owners may use the raw pool while retaining their active coordination pin. */
    internal fun bootstrapStorage(dataSource: HikariDataSource): HikariDataSource =
        PinnedPlatformConnection.resolve(poolDelegate(dataSource))

    /** Compares the actual pool beneath request/system wrappers, including configured aliases. */
    internal fun samePool(first: HikariDataSource, second: HikariDataSource): Boolean =
        poolDelegate(first) === poolDelegate(second)

    private fun poolDelegate(dataSource: HikariDataSource): HikariDataSource {
        var current = dataSource
        while (true) {
            val next = current.unwrap(HikariDataSource::class.java) ?: return current
            if (next === current) return current
            current = next
        }
    }

    /**
     * Wraps a datasource owned by a trusted internal subsystem such as a Hazelcast MapStore.
     * Unlike request-scoped wrappers, these connections always receive the explicit system RLS
     * context because their work executes later on Hazelcast threads with no servlet ThreadLocal.
     */
    public fun wrapWithSystemContext(hds: HikariDataSource): HikariDataSource {
        return systemWrappers.getOrPut(hds) {
            RLSAwareHikariDataSource(hds) { RLSRequestContext.systemContext }
        }
    }
}

private class RLSAwareHikariDataSource(
    private val delegate: HikariDataSource,
    private val contextProvider: () -> RLSConnectionContext?,
) : HikariDataSource() {

    override fun getConnection(): Connection = applyContext(delegate.connection)

    override fun getConnection(username: String?, password: String?): Connection =
        applyContext(delegate.getConnection(username, password))

    // A failed context application must never return a privileged connection to a request.
    @Suppress("TooGenericExceptionCaught")
    private fun applyContext(connection: Connection): Connection = try {
        RLSConnectionSql.applyContext(connection, contextProvider() ?: RLSRequestContext.anonymousContext)
        rlsAwareConnection(connection, delegate)
    } catch (exception: Exception) {
        withFailureCleanup(connection::close) {
            withFailureCleanup({ delegate.evictConnection(connection) }) { throw exception }
        }
    }

    override fun close() {
        delegate.close()
    }

    override fun isClosed(): Boolean = delegate.isClosed

    override fun isRunning(): Boolean = delegate.isRunning

    /**
     * Hikari evicts only its own proxy connections, so evicting an RLS proxy through the inherited method (or after
     * unwrapping it, which yields the proxy again) did nothing. A wrapper around this one (MapstoresPod and
     * ParticipantStatsCache wrap the request-scoped platform pool in a system wrapper) evicts through here when its
     * context cannot be applied or cleared; the physical connection beneath the proxy must leave the real pool.
     */
    override fun evictConnection(connection: Connection) {
        val handler = if (Proxy.isProxyClass(connection.javaClass)) Proxy.getInvocationHandler(connection) else null
        if (handler is RLSConnectionHandler) handler.evict() else delegate.evictConnection(connection)
    }

    override fun <T : Any?> unwrap(iface: Class<T>?): T {
        if (iface != null && iface.isInstance(delegate)) {
            @Suppress("UNCHECKED_CAST")
            return delegate as T
        }
        return delegate.unwrap(iface)
    }

    override fun isWrapperFor(iface: Class<*>?): Boolean {
        return iface != null && (iface.isInstance(delegate) || delegate.isWrapperFor(iface))
    }

    override fun toString(): String = "RLSAwareHikariDataSource($delegate)"
}

internal object RLSConnectionSql {
    private const val SET_RLS_CONTEXT_SQL = """
        SELECT
            set_config('app.current_user_id', ?, false),
            set_config('app.authorized_studies', ?, false),
            set_config('app.is_admin', ?, false),
            set_config('app.authorized_organizations', ?, false),
            set_config('app.is_deletion_worker', ?, false),
            set_config('app.receipt_owner_kind', ?, false)
    """

    private const val CLEAR_RLS_CONTEXT_SQL = """
        SELECT
            set_config('app.current_user_id', '', false),
            set_config('app.authorized_studies', '', false),
            set_config('app.is_admin', 'false', false),
            set_config('app.authorized_organizations', '', false),
            set_config('app.is_deletion_worker', 'false', false),
            set_config('app.receipt_owner_kind', '', false)
    """

    fun readSetting(connection: Connection, setting: String): String =
        connection.prepareStatement("SELECT coalesce(current_setting(?, true), '')").use { statement ->
            statement.setString(1, setting)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getString(1) ?: "" else ""
            }
        }

    fun authorizeCreatedObject(connection: Connection, setting: String, id: UUID) {
        check(!connection.autoCommit) { "A created object is authorized only inside its creating transaction" }
        connection.prepareStatement(
            "SELECT set_config(?, concat_ws(',', NULLIF(current_setting(?, true), ''), ?), true)",
        ).use { statement ->
            statement.setString(1, setting)
            statement.setString(2, setting)
            statement.setString(3, id.toString())
            statement.execute()
        }
    }

    fun applyContext(connection: Connection, context: RLSConnectionContext) {
        connection.prepareStatement(SET_RLS_CONTEXT_SQL).use { stmt ->
            stmt.setString(1, context.principalId)
            stmt.setString(2, context.authorizedStudiesCsv)
            stmt.setString(3, context.isAdmin.toString())
            stmt.setString(4, context.authorizedOrganizationsCsv)
            stmt.setString(5, context.isDeletionWorker.toString())
            stmt.setString(6, context.receiptOwnerKind?.name.orEmpty())
            stmt.execute()
        }
        // Apply the application role consistently, including anonymous borrows.
        // Runtime logins are also restricted; RESET ROLE in clear() cannot elevate them.
        RLSDataSources.appRole?.let { role ->
            connection.createStatement().use { stmt -> stmt.execute("SET ROLE \"$role\"") }
        }
    }

    fun clear(connection: Connection) {
        // Restore the pool's authenticated (privileged) role before clearing context so
        // the physical connection is reusable for bootstrap/non-request work.
        //
        // RESET ROLE is issued UNCONDITIONALLY (not gated on appRole) — `appRole` is a
        // @Volatile var read independently here and in applyContext, so gating both reads on
        // it opens a TOCTOU: if appRole is flipped to null between applyContext (which already
        // ran SET ROLE) and here, the RESET would be skipped and the connection would return
        // to the pool still SET ROLE'd, silently running the next (bootstrap/DDL) borrower as
        // the restricted role. RESET ROLE is a harmless no-op when no role was set, so always
        // running it is the correct fail-safe.
        connection.createStatement().use { stmt -> stmt.execute("RESET ROLE") }
        connection.prepareStatement(CLEAR_RLS_CONTEXT_SQL).use { stmt ->
            stmt.execute()
        }
    }
}

private fun rlsAwareConnection(connection: Connection, hds: HikariDataSource): Connection =
    Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf(Connection::class.java),
        RLSConnectionHandler(connection, hds),
    ) as Connection

// reason: security-critical JDBC proxy dispatch; the when-over-method-name branching is the
// minimal correct form and must not be restructured (the handler owns connection/closed/hds).
// SpreadOperator: java.lang.reflect.Method.invoke is a vararg reflection API requiring spread.
@Suppress("SpreadOperator")
private class RLSConnectionHandler(
    private val connection: Connection,
    private val hds: HikariDataSource,
) : InvocationHandler {
    private val closed = AtomicBoolean(false)

    /** Removes the physical connection from its pool without the RLS reset a broken session cannot run. */
    fun evict() {
        if (closed.compareAndSet(false, true)) {
            withFailureCleanup(connection::close) { hds.evictConnection(connection) }
        }
    }

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? =
        when (method.name) {
            "close" -> {
                if (closed.compareAndSet(false, true)) {
                    clearAndClose(connection, hds)
                }
                Unit
            }
            "unwrap" -> {
                val iface = args?.getOrNull(0) as? Class<*>
                if (iface != null && iface.isInstance(proxy)) {
                    proxy
                } else {
                    connection.unwrap(iface)
                }
            }
            "isWrapperFor" -> {
                val iface = args?.getOrNull(0) as? Class<*>
                iface != null && (iface.isInstance(proxy) || connection.isWrapperFor(iface))
            }
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "RLSAwareConnection($connection)"
            else -> try {
                method.invoke(connection, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
}

// reason: resource-cleanup boundary — any failure resetting/clearing the connection must evict
// it and surface a safe SQLException that retains retry classification.
@Suppress("TooGenericExceptionCaught")
private fun clearAndClose(connection: Connection, hds: HikariDataSource) {
    try {
        if (!connection.autoCommit) {
            connection.rollback()
            connection.autoCommit = true
        }
        RLSConnectionSql.clear(connection)
        connection.close()
    } catch (e: Exception) {
        val failure = e.sqlCause()?.let(SqlFailures::sanitize)
        withFailureCleanup({ hds.evictConnection(connection) }) {
            throw SQLException("Failed to clear RLS context before returning connection to pool", failure?.sqlState, failure)
        }
    }
}
