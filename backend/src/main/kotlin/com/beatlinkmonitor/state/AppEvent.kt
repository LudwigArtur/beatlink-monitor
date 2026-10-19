package com.beatlinkmonitor.state

import com.beatlinkmonitor.model.MasterClockState
import com.beatlinkmonitor.model.PlayerState

/**
 * Discrete state-change events (requirements specification §21). Output modules subscribe
 * only to the events relevant to them instead of polling `beat-link` or the
 * full state directly.
 */
sealed interface AppEvent {
    data class PlayerConnected(val deviceNumber: Int) : AppEvent
    data class PlayerDisconnected(val deviceNumber: Int) : AppEvent
    data class PlayerStateUpdated(val state: PlayerState) : AppEvent
    data class TrackLoaded(val deviceNumber: Int, val trackId: Long?) : AppEvent
    data class TrackChanged(val deviceNumber: Int, val title: String?, val artist: String?) : AppEvent
    data class TempoChanged(val deviceNumber: Int, val effectiveBpm: Double) : AppEvent
    data class MasterChanged(val previous: Int?, val current: Int?) : AppEvent
    data class OnAirChanged(val deviceNumber: Int, val onAir: Boolean) : AppEvent
    data class PlaybackPositionChanged(val deviceNumber: Int, val positionMs: Long) : AppEvent
    data class BeatOccurred(val deviceNumber: Int, val beatNumber: Int) : AppEvent
    data class BarOccurred(val deviceNumber: Int) : AppEvent
    data class MasterClockUpdated(val state: MasterClockState) : AppEvent
}
