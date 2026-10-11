package com.openlattice.chronicle.testing

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** One-shot faults are counted across connections, after preparing and before executing SQL. */
class FaultInjectingDataSource(private val delegate: DataSource) : DataSource by delegate {
    private var statementRule: Pair<Regex, Int>? = null
    private var commitRule: Int? = null
    private val executions = AtomicInteger()
    private val commits = AtomicInteger()
    private val failures = AtomicInteger()
    val injectedFailures: Int get() = failures.get()

    fun failOnStatement(sql: Regex, execution: Int = 1) {
        require(execution > 0)
        clearFault()
        statementRule = sql to execution
    }

    fun failOnCommit(commit: Int = 1) {
        require(commit > 0)
        clearFault()
        commitRule = commit
    }

    fun clearFault() {
        statementRule = null
        commitRule = null
        executions.set(0)
        commits.set(0)
        failures.set(0)
    }

    override fun getConnection(): Connection = wrapConnection(delegate.connection)

    override fun getConnection(username: String?, password: String?): Connection =
        wrapConnection(delegate.getConnection(username, password))

    private fun fail(message: String): Nothing {
        failures.incrementAndGet()
        throw SQLException("Injected fault: $message", "XX999")
    }

    private fun checkSql(sql: String) {
        statementRule?.let { (pattern, nth) ->
            if (pattern.containsMatchIn(sql) && executions.incrementAndGet() == nth) {
                fail("statement execution #$nth")
            }
        }
    }

    private fun wrapConnection(connection: Connection): Connection =
        proxy(Connection::class.java) { method, args ->
            if (method.name == "commit" && commits.incrementAndGet() == commitRule) {
                fail("commit #${commitRule}")
            }
            val result = invoke(connection, method, args)
            when (method.name) {
                "prepareCall" -> wrapStatement(result as CallableStatement, args[0] as String)
                "prepareStatement" -> wrapStatement(result as PreparedStatement, args[0] as String)
                "createStatement" -> wrapStatement(result as Statement, null)
                else -> result
            }
        }

    private fun wrapStatement(statement: Statement, preparedSql: String?): Statement {
        val batches = mutableListOf<String>()
        val type = when (statement) {
            is CallableStatement -> CallableStatement::class.java
            is PreparedStatement -> PreparedStatement::class.java
            else -> Statement::class.java
        }
        return proxy(type) { method, args ->
            when (method.name) {
                "addBatch" -> if (preparedSql == null) batches.add(args[0] as String)
                "clearBatch" -> batches.clear()
                "executeBatch", "executeLargeBatch" -> {
                    if (preparedSql != null) checkSql(preparedSql) else batches.forEach(::checkSql)
                    batches.clear()
                }
                "execute", "executeQuery", "executeUpdate", "executeLargeUpdate" ->
                    checkSql(preparedSql ?: args[0] as String)
            }
            invoke(statement, method, args)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, dispatch: (Method, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            dispatch(method, args ?: emptyArray())
        } as T

    @Suppress("SpreadOperator")
    private fun invoke(target: Any, method: Method, args: Array<out Any?>): Any? =
        try {
            method.invoke(target, *args)
        } catch (exception: InvocationTargetException) {
            throw exception.targetException
        }
}
