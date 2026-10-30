package com.beatlinkmonitor.mixer

import com.beatlinkmonitor.model.DeviceType
import com.beatlinkmonitor.model.PlayerState

/** What a mixer model has on its surface, as far as it was checked on real hardware. */
data class MixerModelInfo(
    val prefix: String,
    val eqBands: Int,
    val channels: Int,
    val hasComp: Boolean,
    val hasSend: Boolean,
    val hasIsolator: Boolean,
    /** What the model calls its per-channel filter knob. */
    val filterName: String,
)

/**
 * What is known about specific mixer models, keyed by the device name the mixer announces. The number of EQ bands
 * decides which `0x39` EQ values drive the mid part of a waveform: a 3-band mixer keeps its single mid knob in
 * Hi-Mid (Lo-Mid stays 0), a 4-band mixer has both knobs. Channel count and the presence of per-channel send and
 * the isolator decide how the mixer is drawn in the UI. Only models that were checked on real hardware are listed;
 * for any other model [find] is null and the UI falls back to detecting the EQ from the data and a plain table.
 */
object MixerModels {
    private val MODELS = listOf(
        // The V10 matches DJM-V10-LF as well.
        MixerModelInfo("DJM-V10", eqBands = 4, channels = 6, hasComp = true, hasSend = true, hasIsolator = true, filterName = "FILTER"),
        MixerModelInfo("DJM-A9", eqBands = 3, channels = 4, hasComp = false, hasSend = false, hasIsolator = false, filterName = "COLOR"),
    )

    fun find(name: String?): MixerModelInfo? {
        val normalized = name?.trim()?.uppercase() ?: return null
        return MODELS.firstOrNull { normalized.startsWith(it.prefix) }
    }

    fun eqBands(name: String?): Int? = find(name)?.eqBands
}

/** The mixer state completed with the model of the mixer currently on the network, for the UI. */
fun MixerState.withModel(players: Collection<PlayerState>): MixerState {
    val name = players.firstOrNull { it.deviceType == DeviceType.MIXER }?.deviceName?.takeIf { it.isNotBlank() }
    val info = MixerModels.find(name)
    return copy(
        model = name,
        eqBands = info?.eqBands,
        channelCount = info?.channels,
        hasComp = info?.hasComp,
        hasSend = info?.hasSend,
        hasIsolator = info?.hasIsolator,
        filterName = info?.filterName,
    )
}
