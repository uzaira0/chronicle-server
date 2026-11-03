package com.openlattice.chronicle.storage

import com.openlattice.chronicle.storage.rls.RLSDataSources
import com.zaxxer.hikari.HikariDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Scopes one already-open platform transaction to the current thread so nested work that borrows
 * `storageResolver.getPlatformStorage().connection` joins that transaction instead of opening a
 * second one.
 *
 * This is what makes a guard (take a lock, evaluate a predicate, then write) atomic across code
 * that was written to own its own connection: the predicate and the write end up in the same
 * transaction on the same connection, so pool occupancy stays at exactly one connection per
 * request. Borrowed connections ignore close, commit, rollback, and setAutoCommit: only the pin
 * owner may end the transaction or close the real connection. Nested transaction-owning helpers
 * therefore leave their work and locks in the owner's transaction until it commits or rolls back.
 */
public object PinnedPlatformConnection {
    private val pinned = ThreadLocal<PinnedHikariDataSource?>()

    /** Returns this thread's pin only when [default] belongs to the same underlying pool. */
    public fun resolve(default: HikariDataSource): HikariDataSource {
        val active = pinned.get()?.takeIf { it.belongsTo(default) } ?: return default
        return active.borrowingDataSource
    }

    internal fun owningConnection(default: HikariDataSource, connection: Connection? = null): Connection? =
        pinned.get()?.takeIf {
            it.belongsTo(default) && it.ownsTransaction && (connection == null || it.ownsConnection(connection))
        }?.ownerConnection

    /** Active transaction pins and session-sharing scopes both support direct routing reads. */
    internal fun activeConnection(default: HikariDataSource): Connection? =
        pinned.get()?.takeIf { it.belongsTo(default) }?.ownerConnection

    internal fun afterCommit(default: HikariDataSource, key: Any, action: () -> Unit) {
        val owner = checkNotNull(pinned.get()?.takeIf { it.belongsTo(default) && it.ownsTransaction })
        owner.afterCommit.putIfAbsent(key, action)
    }

    /** Cache warms read through another connection, so inside a pinned transaction they wait for its commit. */
    internal fun afterCommitOrNow(default: HikariDataSource, key: Any, action: () -> Unit) {
        if (owningConnection(default) != null) afterCommit(default, key, action) else action()
    }

    /** Commit the outermost owner, then dispatch its coalesced cache invalidations. */
    internal fun <T> committing(delegate: HikariDataSource, connection: Connection, commitWhen: () -> Boolean = { true }, block: () -> T): T {
        if (owningConnection(delegate) != null) return pinning(delegate, connection, block)
        return pinning(delegate, connection) {
            val result = block()
            if (commitWhen()) {
                connection.commit()
                checkNotNull(pinned.get()).afterCommit.values.forEach { it() }
            } else connection.rollback()
            result
        }
    }

    /**
     * Runs [block] with [connection] pinned for this thread. Restores any enclosing pin so nested
     * guards cannot erase their caller's transaction.
     */
    public fun <T> pinning(delegate: HikariDataSource, connection: Connection, block: () -> T): T {
        if (owningConnection(delegate) != null) {
            check(owningConnection(delegate, connection) != null) { "Connection does not belong to the active transaction" }
            return block()
        }
        return scoped(delegate, connection, PinScope.TRANSACTION, block)
    }

    /**
     * Reuses a session-lock owner's connection while helpers retain their own transaction commits.
     * The coordination owner must have no uncommitted work when entering this scope.
     */
    internal fun <T> sharing(delegate: HikariDataSource, connection: Connection, block: () -> T): T {
        return scoped(delegate, connection, PinScope.REQUEST_SESSION, block)
    }

    /** Startup recovery reads the owner-only checkpoint while retaining one coordination connection. */
    internal fun <T> bootstrapSharing(
        delegate: HikariDataSource,
        connection: Connection,
        runtimePools: List<HikariDataSource>,
        block: () -> T,
    ): T = scoped(delegate, connection, PinScope.BOOTSTRAP_SESSION, block, runtimePools)

    private fun <T> scoped(
        delegate: HikariDataSource,
        connection: Connection,
        scope: PinScope,
        block: () -> T,
        bootstrapRuntimePools: List<HikariDataSource> = emptyList(),
    ): T {
        if (scope != PinScope.TRANSACTION) check(connection.autoCommit) { "Session connection sharing requires autocommit" }
        val previous = pinned.get()
        pinned.set(PinnedHikariDataSource(delegate, connection, scope, bootstrapRuntimePools))
        return try {
            block()
        } finally {
            if (previous == null) pinned.remove() else pinned.set(previous)
        }
    }
}

private enum class PinScope { TRANSACTION, REQUEST_SESSION, BOOTSTRAP_SESSION }

private class PinnedHikariDataSource(
    private val delegate: HikariDataSource,
    private val pinned: Connection,
    private val scope: PinScope,
    private val bootstrapRuntimePools: List<HikariDataSource>,
) : HikariDataSource() {
    val ownsTransaction: Boolean get() = scope == PinScope.TRANSACTION
    val afterCommit = linkedMapOf<Any, () -> Unit>()
    private val borrowed = Collections.newSetFromMap(IdentityHashMap<Connection, Boolean>())

    // Session sharing keeps each helper's request/deletion-worker RLS context and cleanup.
    val ownerConnection: Connection get() = pinned

    val borrowingDataSource: HikariDataSource = when (scope) {
        PinScope.REQUEST_SESSION -> RLSDataSources.wrapUncachedRequestScoped(this)
        PinScope.TRANSACTION, PinScope.BOOTSTRAP_SESSION -> this
    }

    fun belongsTo(dataSource: HikariDataSource): Boolean = RLSDataSources.samePool(delegate, dataSource) ||
        (scope == PinScope.BOOTSTRAP_SESSION && bootstrapRuntimePools.any { RLSDataSources.samePool(it, dataSource) })

    fun ownsConnection(connection: Connection): Boolean = connection === pinned || connection in borrowed

    override fun getConnection(): Connection = nonClosingConnection(pinned, ownsTransaction).also { borrowed.add(it) }

    override fun getConnection(username: String?, password: String?): Connection =
        connection

    override fun close() {
        // The pin owner closes the real connection.
    }

    override fun isClosed(): Boolean = delegate.isClosed

    override fun isRunning(): Boolean = delegate.isRunning

    override fun evictConnection(connection: Connection) {
        delegate.evictConnection(pinned)
    }

    override fun <T : Any?> unwrap(iface: Class<T>?): T {
        if (iface != null && iface.isInstance(delegate)) {
            @Suppress("UNCHECKED_CAST")
            return delegate as T
        }
        return delegate.unwrap(iface)
    }

    override fun isWrapperFor(iface: Class<*>?): Boolean =
        iface != null && (iface.isInstance(delegate) || delegate.isWrapperFor(iface))

    override fun toString(): String = "PinnedHikariDataSource($delegate)"
}

// reason: JDBC proxy dispatch; the when-over-method-name branching is the minimal correct form.
@Suppress("SpreadOperator")
private fun nonClosingConnection(connection: Connection, ownsTransaction: Boolean): Connection {
    val handler = java.lang.reflect.InvocationHandler { proxy, method, args ->
        when (method.name) {
            "close" -> Unit
            "setAutoCommit", "commit", "rollback" -> if (ownsTransaction) Unit else {
                try {
                    method.invoke(connection, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
            "isClosed" -> false
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "PinnedConnection($connection)"
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
        handler,
    ) as Connection
}
