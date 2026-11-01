package com.beatlinkmonitor.prodjlink

import kotlin.test.Test
import kotlin.test.assertEquals

class RestartBackoffTest {
    private val backoff = RestartBackoff(baseMs = 180_000L, maxMs = 1_800_000L)

    @Test
    fun `every restart that did not help doubles the wait up to the limit`() {
        assertEquals(180_000L, backoff.cooldownMs())
        backoff.restarted()
        assertEquals(360_000L, backoff.cooldownMs())
        backoff.restarted()
        assertEquals(720_000L, backoff.cooldownMs())
        repeat(10) { backoff.restarted() }
        assertEquals(1_800_000L, backoff.cooldownMs())
    }

    @Test
    fun `a healthy check starts over`() {
        repeat(3) { backoff.restarted() }

        backoff.recovered()

        assertEquals(180_000L, backoff.cooldownMs())
    }
}
