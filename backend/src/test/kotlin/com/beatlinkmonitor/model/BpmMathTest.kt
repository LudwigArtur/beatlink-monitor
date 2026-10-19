package com.beatlinkmonitor.model

import kotlin.test.Test
import kotlin.test.assertEquals

class BpmMathTest {

    @Test
    fun `effective bpm with zero pitch equals track bpm`() {
        assertEquals(140.0, BpmMath.effectiveBpm(140.0, 0.0), 0.0001)
    }

    @Test
    fun `effective bpm with positive pitch (Scenario B)`() {
        // requirements specification §39.2 Scenario B: 140 BPM, +1% => ~141.40
        val result = BpmMath.effectiveBpm(140.0, 1.0)
        assertEquals(141.40, result, 0.01)
    }

    @Test
    fun `effective bpm with documented example`() {
        // requirements specification §7.2 example: 140 BPM, +0.73% => 141.022
        val result = BpmMath.effectiveBpm(140.0, 0.73)
        assertEquals(141.022, result, 0.001)
    }

    @Test
    fun `effective bpm with negative pitch`() {
        val result = BpmMath.effectiveBpm(128.0, -6.0)
        assertEquals(120.32, result, 0.01)
    }

    @Test
    fun `display rounding uses two decimals by default`() {
        assertEquals(141.02, BpmMath.format(141.021978), 0.0)
    }

    @Test
    fun `display rounding is configurable decimal count`() {
        assertEquals(141.0, BpmMath.format(141.021978, 0), 0.0)
        assertEquals(141.022, BpmMath.format(141.021978, 3), 0.0)
    }

    @Test
    fun `format string never rounds early for the underlying value`() {
        // The formatted string is rounded only for display, the caller's
        // original double is unaffected (coding guideline #12).
        val original = BpmMath.effectiveBpm(140.0, 0.734567)
        val formatted = BpmMath.formatString(original, 2)
        assertEquals("141.03", formatted)
        assertEquals(141.0283938, original, 0.0001)
    }

    @Test
    fun `int bpm rounds to nearest whole number`() {
        assertEquals(141, BpmMath.intBpm(141.4))
        assertEquals(140, BpmMath.intBpm(140.49))
    }
}
