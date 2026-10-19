package com.beatlinkmonitor.waveform

import com.beatlinkmonitor.model.WaveformType
import kotlin.test.Test
import kotlin.test.assertEquals

class WaveformFallbackTest {

    @Test
    fun `prefers three band when available`() {
        assertEquals(WaveformType.THREE_BAND, WaveformFallback.resolve(true, true, true))
    }

    @Test
    fun `falls back to RGB when three band is unavailable (Scenario E)`() {
        assertEquals(WaveformType.RGB, WaveformFallback.resolve(false, true, true))
    }

    @Test
    fun `falls back to classic when only classic is available`() {
        assertEquals(WaveformType.CLASSIC, WaveformFallback.resolve(false, false, true))
    }

    @Test
    fun `falls back to none when nothing is available`() {
        assertEquals(WaveformType.NONE, WaveformFallback.resolve(false, false, false))
    }
}
