package com.openlattice.chronicle.e2e.dsl.cleanup

import org.slf4j.LoggerFactory

class CleanupStack {
    private val log = LoggerFactory.getLogger(CleanupStack::class.java)
    private val stack = ArrayDeque<() -> Unit>()

    fun push(action: () -> Unit) {
        stack.addLast(action)
    }

    fun runAll(swallowExceptions: Boolean = true) {
        while (stack.isNotEmpty()) {
            val action = stack.removeLast()
            try {
                action()
            } catch (e: Exception) {
                if (swallowExceptions) {
                    log.warn("Cleanup action failed (continuing teardown)", e)
                } else {
                    throw e
                }
            }
        }
    }
}
