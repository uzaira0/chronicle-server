package com.openlattice.chronicle

import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Duration

private val BLOCKED_BY_BARRIER = """
    SELECT count(*) FROM pg_stat_activity waiting
    WHERE pg_backend_pid() = ANY(pg_blocking_pids(waiting.pid))
       OR EXISTS (SELECT 1 FROM pg_stat_activity held
                  WHERE pg_backend_pid() = ANY(pg_blocking_pids(held.pid)) AND held.pid = ANY(pg_blocking_pids(waiting.pid)))
""".trimIndent()

/** Waits until [expected] sessions are blocked by the [barrier], directly or behind a session it blocks. */
fun awaitSessionsBlockedBy(barrier: Connection, expected: Int, timeout: Duration = Duration.ofSeconds(30)) {
    val deadline = System.nanoTime() + timeout.toNanos()
    barrier.prepareStatement(BLOCKED_BY_BARRIER).use { statement ->
        while (System.nanoTime() < deadline) {
            if (blockedCount(statement) >= expected) return
            Thread.sleep(10)
        }
    }
    throw AssertionError("expected $expected sessions queued behind the barrier")
}

private fun blockedCount(statement: PreparedStatement): Int = statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
