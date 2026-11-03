package com.openlattice.chronicle

import io.github.bucket4j.TimeMeter
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Normal pre-start Spring composition for the shared live server's quota time source. */
@Configuration(proxyBeanMethods = false)
class RateLimitTestClockPod {
    @Bean("preAuthenticationRateLimitClock")
    fun preAuthenticationRateLimitClock(): RateLimitTestClock = RateLimitTestClock()
}

/** Wall-clock time normally; a test scope can hold it steady without replacing real bucket managers. */
class RateLimitTestClock(private val source: TimeMeter = TimeMeter.SYSTEM_MILLISECONDS) : TimeMeter {
    @Volatile private var frozenNanos: Long? = null

    override fun currentTimeNanos(): Long = frozenNanos ?: source.currentTimeNanos()

    override fun isWallClockBased(): Boolean = source.isWallClockBased()

    @Synchronized
    fun whileFrozen(operation: () -> Unit) {
        check(frozenNanos == null) { "Quota clock is already frozen" }
        frozenNanos = source.currentTimeNanos()
        try {
            operation()
        } finally {
            frozenNanos = null
        }
    }
}

class RateLimitTestClockTest {
    @Test
    fun scopedClockFixesValuesRejectsNestedFreezeAndRestoresAfterSuccessAndException() {
        val now = AtomicLong(10L)
        val source = object : TimeMeter {
            override fun currentTimeNanos(): Long = now.get()
            override fun isWallClockBased(): Boolean = true
        }
        val clock = RateLimitTestClock(source)
        assertTrue(clock.isWallClockBased())
        assertEquals(10L, clock.currentTimeNanos())
        now.set(20L)
        assertEquals(20L, clock.currentTimeNanos())
        clock.whileFrozen {
            assertEquals(20L, clock.currentTimeNanos())
            now.set(30L)
            assertEquals(20L, clock.currentTimeNanos())
            assertThrows(IllegalStateException::class.java) {
                clock.whileFrozen { throw AssertionError("Nested freeze ran") }
            }
            assertEquals(20L, clock.currentTimeNanos())
        }
        assertEquals(30L, clock.currentTimeNanos())
        now.set(40L)
        val expected = IllegalArgumentException("clock-scope-fixture")
        val actual = assertThrows(IllegalArgumentException::class.java) {
            clock.whileFrozen {
                assertEquals(40L, clock.currentTimeNanos())
                now.set(50L)
                assertEquals(40L, clock.currentTimeNanos())
                throw expected
            }
        }
        assertSame(expected, actual)
        assertEquals(50L, clock.currentTimeNanos())
    }
}
