package com.openlattice.chronicle.storage

import com.zaxxer.hikari.HikariDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection

/**
 * Scopes one already-open platform transaction to the current thread so nested work that borrows
 * `storageResolver.getPlatformStorage().connection` joins that transaction instead of opening a
 * second one.
 *
 * This is what makes a guard (take a lock, evaluate a predicate, then write) atomic across code
 * that was written to own its own connection: the predicate and the write end up in the same
 * transaction on the same connection, so pool occupancy stays at exactly one connection per
 * request. Only [Connection.close] is intercepted (it becomes a no-op, because the owner of the
 * pin closes the real connection); commit/rollback/autoCommit pass through unchanged so callers
 * that manage their own transaction still commit the guarded work as one unit.
 */
public object PinnedPlatformConnection {
    private val pinned = ThreadLocal<HikariDataSource?>()

    /** Returns the pinned datasource for this thread, or [default] when nothing is pinned. */
    public fun resolve(default: HikariDataSource): HikariDataSource = pinned.get() ?: default

    /**
     * Runs [block] with [connection] pinned for this thread. Restores any enclosing pin so nested
     * guards cannot erase their caller's transaction.
     */
    public fun <T> pinning(delegate: HikariDataSource, connection: Connection, block: () -> T): T {
        val previous = pinned.get()
        pinned.set(PinnedHikariDataSource(delegate, connection))
        return try {
            block()
        } finally {
            if (previous == null) pinned.remove() else pinned.set(previous)
        }
    }
}

private class PinnedHikariDataSource(
    private val delegate: HikariDataSource,
    private val pinned: Connection,
) : HikariDataSource() {
    override fun getConnection(): Connection = nonClosingConnection(pinned)

    override fun getConnection(username: String?, password: String?): Connection = nonClosingConnection(pinned)

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
private fun nonClosingConnection(connection: Connection): Connection {
    val handler = java.lang.reflect.InvocationHandler { proxy, method, args ->
        when (method.name) {
            "close" -> Unit
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
