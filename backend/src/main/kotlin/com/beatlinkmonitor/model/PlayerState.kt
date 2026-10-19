package com.beatlinkmonitor.model

import kotlinx.serialization.Serializable

/**
 * Indicates why a field on [PlayerState] currently has no value, so the UI
 * can distinguish "not supported by this hardware/protocol" from "not yet known"
 * instead of guessing or inventing data (requirements specification §6).
 */
@Serializable
enum class Availability {
    AVAILABLE,
    UNKNOWN,
    UNSUPPORTED,
}

@Serializable
enum class DeviceType {
    CDJ,
    MIXER,
    REKORDBOX,
    UNKNOWN,
}

@Serializable
enum class WaveformType {
    RGB,
    THREE_BAND,
    CLASSIC,
    NONE,
}

/**
 * Central per-player state, fed either by the real Pro DJ Link network
 * (via [com.beatlinkmonitor.prodjlink.ProDjLinkAdapter]) or by the
 * [com.beatlinkmonitor.simulator.SimulationService]. Both sources populate the
 * exact same model so downstream consumers (UI, outputs) never need to care
 * where the data came from (requirements specification §17.1, §20, coding guideline #6).
 */
@Serializable
data class PlayerState(
    val deviceNumber: Int,
    val deviceName: String = "",
    val deviceType: DeviceType = DeviceType.UNKNOWN,
    val ipAddress: String? = null,
    val firmwareVersion: String? = null,
    val connected: Boolean = false,
    val playing: Boolean = false,
    val paused: Boolean = false,
    val onAir: Boolean = false,
    // Directly reported by real Pro DJ Link hardware via `CdjStatus`'s
    // `PlayState1.LOOPING` value - not inferred/guessed (coding guideline
    // #13). Mixers and the simulator never report this (no loop concept
    // there), so it simply stays `false` for them.
    val looping: Boolean = false,
    // Pro DJ Link's status packets only ever report *whether* a loop is
    // active, never its actual boundaries/duration. These are instead
    // derived from directly-observed hardware behavior (not invented data,
    // coding guideline #13): while looping, the real, continuously
    // reported playback position necessarily jumps backward by exactly the
    // loop's length every time it wraps around - `ProDjLinkAdapter` detects
    // that exact jump and records its before/after positions here. Stay
    // `null` until the first such wrap has actually been observed (right
    // when a loop starts, its length is not yet known), and are cleared
    // again as soon as the loop ends.
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null,
    val tempoMaster: Boolean = false,
    val rekordboxTrackId: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    val originalBpm: Double? = null,
    val pitchPercent: Double? = null,
    val effectiveBpm: Double? = null,
    val beatNumber: Int? = null,
    val beatWithinBar: Int? = null,
    val playbackPositionMs: Long? = null,
    val trackDurationMs: Long? = null,
    val waveformType: WaveformType = WaveformType.NONE,
    val hasWaveformPreview: Boolean = false,
    val hasWaveformDetail: Boolean = false,
    val hasBeatGrid: Boolean = false,
    val lastUpdateTimestamp: Long = 0L,
    val dataAvailability: Map<String, Availability> = emptyMap(),
    val source: PlayerSource = PlayerSource.REAL,
) {
    companion object {
        /** A freshly discovered but not-yet-populated player. */
        fun empty(deviceNumber: Int, source: PlayerSource = PlayerSource.REAL): PlayerState =
            PlayerState(deviceNumber = deviceNumber, source = source)
    }
}

@Serializable
enum class PlayerSource {
    REAL,
    SIMULATED,
}
