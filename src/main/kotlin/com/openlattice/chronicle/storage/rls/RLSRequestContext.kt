package com.openlattice.chronicle.storage.rls

import com.zaxxer.hikari.HikariDataSource
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

public data class RLSConnectionContext(
    val principalId: String,
    val authorizedStudyIds: Set<UUID>,
    val isAdmin: Boolean,
    val authorizedOrganizationIds: Set<UUID> = emptySet(),
) {
    public val authorizedStudiesCsv: String = authorizedStudyIds.joinToString(",") { it.toString() }
    public val authorizedOrgsCsv: String = authorizedOrganizationIds.joinToString(",") { it.toString() }
}

public object RLSRequestContext {
    private val currentContext = ThreadLocal<RLSConnectionContext?>()
    private val systemContext = RLSConnectionContext(
        principalId = "chronicle-background",
        authorizedStudyIds = emptySet(),
        isAdmin = true,
    )
    private val deletionWorkerContext = RLSConnectionContext(
        principalId = "chronicle-deletion-worker",
        authorizedStudyIds = emptySet(),
        isAdmin = true,
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

    /**
     * Runs one asynchronous export with the same least-privilege study boundary
     * as the request that created it. Executor threads do not inherit servlet
     * ThreadLocals, so leaving this implicit would make the pool owner bypass
     * study and deletion-quarantine RLS policies.
     */
    public fun <T> withExportWorkerContext(
        principalId: String,
        studyId: UUID,
        block: () -> T,
    ): T {
        require(principalId.isNotBlank()) { "Export worker principal must not be blank" }
        return withContext(
            RLSConnectionContext(
                principalId = principalId,
                authorizedStudyIds = setOf(studyId),
                isAdmin = false,
            ),
            block,
        )
    }

    private fun <T> withContext(context: RLSConnectionContext, block: () -> T): T {
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
     * Non-superuser Postgres role the request path drops to (via `SET ROLE`) before
     * running any query, restoring the pool's authenticated role (`RESET ROLE`) on
     * return. This is what makes Row-Level Security and the role-level audit-immutability
     * REVOKEs actually take effect: the connection pool authenticates as the
     * owner/superuser `chronicle` (required for schema bootstrap and the upgrade
     * runner), and PostgreSQL lets a superuser bypass RLS entirely — so without this
     * drop every per-request `app.authorized_studies` setting is silently inert.
     *
     * `SET ROLE` only fires when a request context is present, so the bootstrap and
     * upgrade paths (which run with no [RLSRequestContext]) keep full privileges.
     *
     * Control-plane *creation* flows (study / candidate / organization) are made
     * RLS-correct by V28 (`ControlPlaneInsertRlsUpgrade`), which splits the creation-path
     * policies so INSERT is permissive while SELECT/UPDATE/DELETE stay study-isolated — so
     * this drop is engaged by default. Set to `null` to disable (e.g. an environment whose
     * pool already authenticates as the restricted role). Validated as a SQL identifier.
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
        return wrappers.getOrPut(hds) { RLSAwareHikariDataSource(hds, RLSRequestContext::current) }
    }

    /**
     * Wraps a datasource owned by a trusted internal subsystem such as a Hazelcast MapStore.
     * Unlike request-scoped wrappers, these connections always receive the explicit system RLS
     * context because their work executes later on Hazelcast threads with no servlet ThreadLocal.
     */
    public fun wrapWithSystemContext(hds: HikariDataSource): HikariDataSource {
        return systemWrappers.getOrPut(hds) {
            RLSAwareHikariDataSource(hds) {
                RLSConnectionContext(
                    principalId = "chronicle-background",
                    authorizedStudyIds = emptySet(),
                    isAdmin = true,
                )
            }
        }
    }
}

private class RLSAwareHikariDataSource(
    private val delegate: HikariDataSource,
    private val contextProvider: () -> RLSConnectionContext?,
) : HikariDataSource() {

    // reason: resource-cleanup boundary — any failure applying the RLS context must evict the
    // tainted connection rather than return it to the pool; both branches rethrow unchanged
    @Suppress("TooGenericExceptionCaught")
    override fun getConnection(): Connection {
        val connection = delegate.connection
        val context = contextProvider() ?: return connection
        return try {
            RLSConnectionSql.applyContext(connection, context)
            rlsAwareConnection(connection, delegate)
        } catch (e: SQLException) {
            delegate.evictConnection(connection)
            connection.close()
            throw e
        } catch (e: RuntimeException) {
            delegate.evictConnection(connection)
            connection.close()
            throw e
        }
    }

    // reason: resource-cleanup boundary — any failure applying the RLS context must evict the
    // tainted connection rather than return it to the pool; both branches rethrow unchanged
    @Suppress("TooGenericExceptionCaught")
    override fun getConnection(username: String?, password: String?): Connection {
        val connection = delegate.getConnection(username, password)
        val context = contextProvider() ?: return connection
        return try {
            RLSConnectionSql.applyContext(connection, context)
            rlsAwareConnection(connection, delegate)
        } catch (e: SQLException) {
            delegate.evictConnection(connection)
            connection.close()
            throw e
        } catch (e: RuntimeException) {
            delegate.evictConnection(connection)
            connection.close()
            throw e
        }
    }

    override fun close() {
        delegate.close()
    }

    override fun isClosed(): Boolean = delegate.isClosed

    override fun isRunning(): Boolean = delegate.isRunning

    override fun evictConnection(connection: Connection) {
        val unwrapped = try {
            connection.unwrap(Connection::class.java)
        } catch (_: SQLException) {
            connection
        }
        delegate.evictConnection(unwrapped)
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

private object RLSConnectionSql {
    private const val SET_RLS_CONTEXT_SQL = """
        SELECT
            set_config('app.current_user_id', ?, false),
            set_config('app.authorized_studies', ?, false),
            set_config('app.is_admin', ?, false),
            set_config('app.authorized_orgs', ?, false)
    """

    private const val CLEAR_RLS_CONTEXT_SQL = """
        SELECT
            set_config('app.current_user_id', '', false),
            set_config('app.authorized_studies', '', false),
            set_config('app.is_admin', 'false', false),
            set_config('app.authorized_orgs', '', false)
    """

    fun applyContext(connection: Connection, context: RLSConnectionContext) {
        connection.prepareStatement(SET_RLS_CONTEXT_SQL).use { stmt ->
            stmt.setString(1, context.principalId)
            stmt.setString(2, context.authorizedStudiesCsv)
            stmt.setString(3, context.isAdmin.toString())
            stmt.setString(4, context.authorizedOrgsCsv)
            stmt.execute()
        }
        // Drop to the non-superuser application role so the RLS policies and the
        // role-level REVOKEs (audit immutability) actually engage on this request.
        // The pool authenticates as the owner/superuser, which bypasses RLS, so the
        // context set above does nothing without this. RESET ROLE happens in clear().
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

// reason: security-critical JDBC proxy dispatch; the when-over-method-name branching is the
// minimal correct form and must not be restructured (closure over connection/closed/hds).
// SpreadOperator: java.lang.reflect.Method.invoke is a vararg reflection API requiring spread.
@Suppress("CyclomaticComplexMethod", "SpreadOperator")
private fun rlsAwareConnection(connection: Connection, hds: HikariDataSource): Connection {
    val closed = AtomicBoolean(false)
    val handler = InvocationHandler { proxy, method, args ->
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

    return Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf(Connection::class.java),
        handler
    ) as Connection
}

// reason: resource-cleanup boundary — any failure resetting/clearing the connection must evict
// it and surface as a single wrapped SQLException; e is chained into the rethrow
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
        hds.evictConnection(connection)
        throw SQLException("Failed to clear RLS context before returning connection to pool", e)
    }
}
