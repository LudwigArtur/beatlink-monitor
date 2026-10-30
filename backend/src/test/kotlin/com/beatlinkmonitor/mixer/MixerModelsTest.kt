package com.beatlinkmonitor.mixer

import com.beatlinkmonitor.model.DeviceType
import com.beatlinkmonitor.model.PlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MixerModelsTest {
    @Test
    fun `models checked on real hardware have a known number of EQ bands`() {
        assertEquals(4, MixerModels.eqBands("DJM-V10"))
        assertEquals(4, MixerModels.eqBands("DJM-V10-LF"))
        assertEquals(3, MixerModels.eqBands("DJM-A9"))
        assertEquals(3, MixerModels.eqBands(" djm-a9 "))
        assertEquals(6, MixerModels.find("DJM-V10")?.channels)
        assertEquals(true, MixerModels.find("DJM-V10")?.hasIsolator)
    }

    @Test
    fun `unknown or missing models are not guessed`() {
        assertNull(MixerModels.eqBands("DJM-900NXS2"))
        assertNull(MixerModels.eqBands(""))
        assertNull(MixerModels.eqBands(null))
    }

    @Test
    fun `the published state carries the model of the mixer on the network`() {
        val players = listOf(
            PlayerState.empty(1).copy(deviceType = DeviceType.CDJ, deviceName = "CDJ-3000"),
            PlayerState.empty(33).copy(deviceType = DeviceType.MIXER, deviceName = "DJM-A9"),
        )

        val state = MixerState().withModel(players)

        assertEquals("DJM-A9", state.model)
        assertEquals(3, state.eqBands)
        assertEquals(4, state.channelCount)
        assertEquals(false, state.hasComp)
        assertEquals("COLOR", state.filterName)
        assertEquals(false, state.hasSend)
        assertEquals(false, state.hasIsolator)
        assertNull(MixerState().withModel(players.take(1)).model)
    }
}
