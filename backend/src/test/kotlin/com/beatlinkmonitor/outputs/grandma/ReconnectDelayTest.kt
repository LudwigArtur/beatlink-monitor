package com.beatlinkmonitor.outputs.grandma

import kotlin.test.Test
import kotlin.test.assertEquals

class ReconnectDelayTest {
    @Test
    fun `the delay doubles from three seconds up to thirty`() {
        assertEquals(listOf(3_000L, 3_000L, 6_000L, 12_000L, 24_000L, 30_000L, 30_000L), (0..6).map { ReconnectDelay.afterFailures(it) })
        assertEquals(30_000L, ReconnectDelay.afterFailures(10_000))
    }
}
