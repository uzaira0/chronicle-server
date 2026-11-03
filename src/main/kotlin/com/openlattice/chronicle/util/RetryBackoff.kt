package com.openlattice.chronicle.util

import java.util.concurrent.ThreadLocalRandom

/** Background retry delay shared by webhook deliveries and exports. */
public object RetryBackoff {
    private const val MAX_DOUBLINGS = 5
    private const val CAP_SECONDS = 30L

    /**
     * Full jitter: uniform in [1, cap] seconds, where the cap doubles per prior attempt up to [CAP_SECONDS],
     * so retries after a shared outage spread out instead of arriving together.
     */
    public fun delaySeconds(priorAttempts: Int): Long {
        val cap = (1L shl priorAttempts.coerceIn(0, MAX_DOUBLINGS)).coerceAtMost(CAP_SECONDS)
        return ThreadLocalRandom.current().nextLong(1, cap + 1)
    }
}
