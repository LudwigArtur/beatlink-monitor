package com.beatlinkmonitor.mixer

import com.beatlinkmonitor.model.DeviceType
import com.beatlinkmonitor.model.PlayerState

/**
 * What is known about specific mixer models, keyed by the device name the mixer announces. The number of EQ bands
 * decides which `0x39` EQ values drive the mid part of a waveform: a 3-band mixer keeps its single mid knob in
 * Hi-Mid (Lo-Mid stays 0), a 4-band mixer has both knobs. Only models that were checked on real hardware are listed;
 * for any other model [eqBands] is null and the UI falls back to detecting it from the data.
 */
object MixerModels {
    private val EQ_BANDS = listOf(
        "DJM-V10" to 4, // also matches DJM-V10-LF
        "DJM-A9" to 3,
    )

    fun eqBands(name: String?): Int? {
        val normalized = name?.trim()?.uppercase() ?: return null
        return EQ_BANDS.firstOrNull { (prefix, _) -> normalized.startsWith(prefix) }?.second
    }
}

/** The mixer state completed with the model of the mixer currently on the network, for the UI. */
fun MixerState.withModel(players: Collection<PlayerState>): MixerState {
    val name = players.firstOrNull { it.deviceType == DeviceType.MIXER }?.deviceName?.takeIf { it.isNotBlank() }
    return copy(model = name, eqBands = MixerModels.eqBands(name))
}
