package com.beatlinkmonitor.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogThrottleTest {
    private var now = 1_000L
    private val throttle = LogThrottle(intervalMs = 60_000L, clock = { now })

    @Test
    fun `the first message passes and repeats within the interval are counted`() {
        assertEquals(0, throttle.tryAcquire("a"))
        repeat(3) { assertNull(throttle.tryAcquire("a")) }

        now += 60_000L
        assertEquals(3, throttle.tryAcquire("a"))
        assertNull(throttle.tryAcquire("a"))
    }

    @Test
    fun `keys are independent and reset lets the next message through at once`() {
        assertEquals(0, throttle.tryAcquire("a"))
        assertEquals(0, throttle.tryAcquire("b"))
        assertNull(throttle.tryAcquire("a"))

        throttle.reset("a")
        assertEquals(0, throttle.tryAcquire("a"))
    }

    @Test
    fun `the suppressed count is appended only when there is one`() {
        assertEquals("failed", LogThrottle.withSuppressed("failed", 0))
        assertEquals("failed (7 similar messages suppressed)", LogThrottle.withSuppressed("failed", 7))
    }
}
