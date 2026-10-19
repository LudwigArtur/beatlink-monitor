package com.beatlinkmonitor.outputs

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {

    @Test
    fun `first value is always sent`() {
        val limiter = RateLimiter(sendOnChange = true, minimumChange = 0.01, maximumRateHz = 10.0)
        assertTrue(limiter.shouldSend(140.0, 0L))
    }

    @Test
    fun `does not send when change is below minimum threshold`() {
        val limiter = RateLimiter(sendOnChange = true, minimumChange = 0.5, maximumRateHz = 100.0)
        assertTrue(limiter.shouldSend(140.0, 0L))
        assertFalse(limiter.shouldSend(140.2, 200L))
    }

    @Test
    fun `sends when change exceeds minimum threshold`() {
        val limiter = RateLimiter(sendOnChange = true, minimumChange = 0.1, maximumRateHz = 100.0)
        assertTrue(limiter.shouldSend(140.0, 0L))
        assertTrue(limiter.shouldSend(140.2, 200L))
    }

    @Test
    fun `enforces maximum send rate even with a large change`() {
        val limiter = RateLimiter(sendOnChange = true, minimumChange = 0.0, maximumRateHz = 10.0) // 100ms min interval
        assertTrue(limiter.shouldSend(120.0, 0L))
        assertFalse(limiter.shouldSend(150.0, 50L))
        assertTrue(limiter.shouldSend(150.0, 150L))
    }

    @Test
    fun `reset clears history so next value is always sent`() {
        val limiter = RateLimiter(sendOnChange = true, minimumChange = 1.0, maximumRateHz = 10.0)
        assertTrue(limiter.shouldSend(140.0, 0L))
        limiter.reset()
        assertTrue(limiter.shouldSend(140.05, 10L))
    }

    @Test
    fun `sendOnChange disabled still honors the rate limit only`() {
        val limiter = RateLimiter(sendOnChange = false, minimumChange = 5.0, maximumRateHz = 10.0)
        assertTrue(limiter.shouldSend(140.0, 0L))
        assertTrue(limiter.shouldSend(140.0, 150L))
    }
}
