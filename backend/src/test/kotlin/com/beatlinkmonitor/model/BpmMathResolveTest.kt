package com.beatlinkmonitor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BpmMathResolveTest {
    @Test
    fun `the effective BPM comes from the track BPM and the pitch when the track BPM is known`() {
        assertEquals(126.063, BpmMath.resolveEffective(126.0, 0.05, 12600, 999.0)!!, 0.0001)
    }

    @Test
    fun `a player without a track does not get a BPM made up from the sentinel`() {
        // 65535 is the "no track" value; 655.35 BPM at -5.1 % pitch used to show up as 621.93.
        assertNull(BpmMath.resolveEffective(null, -5.1, 65535, 621.93))
        assertNull(BpmMath.resolveEffective(null, 0.0, 0, 0.0))
    }

    @Test
    fun `the tempo reported by the device is used when it carries a real BPM`() {
        assertEquals(128.0, BpmMath.resolveEffective(null, 0.0, 12800, 128.0)!!, 0.001)
        assertNull(BpmMath.resolveEffective(null, 0.0, 12800, Double.NaN))
    }
}
