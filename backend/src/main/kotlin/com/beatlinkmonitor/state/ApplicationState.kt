package com.beatlinkmonitor.state

import com.beatlinkmonitor.model.MasterClockState
import com.beatlinkmonitor.model.PlayerState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/**
 * The single source of truth of the whole application (requirements specification §20).
 *
 * Neither the UI, nor GrandMA2, nor Ableton Link are ever allowed to query
 * `beat-link` (or the simulator) directly. Everything flows through here:
 *
 * ```
 * beat-link / simulator -> ApplicationState -> UI / outputs
 * ```
 */
class ApplicationState {

    private val _players = MutableStateFlow<Map<Int, PlayerState>>(emptyMap())
    val players: StateFlow<Map<Int, PlayerState>> = _players.asStateFlow()

    private val _masterClock = MutableStateFlow(MasterClockState.INVALID)
    val masterClock: StateFlow<MasterClockState> = _masterClock.asStateFlow()

    // Replay = 0 on purpose: events are transient notifications, current state
    // is always available via the StateFlows above for late subscribers.
    private val _events = MutableSharedFlow<AppEvent>(replay = 0, extraBufferCapacity = 256)
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    private val networkAvailable = AtomicBoolean(true)

    fun upsertPlayer(deviceNumber: Int, transform: (PlayerState) -> PlayerState) {
        var wasNew = false
        var previousTitle: String? = null
        var previousArtist: String? = null
        var previousOnAir = false
        var previousBpm: Double? = null
        var previousMaster = false

        _players.update { current ->
            val existing = current[deviceNumber]
            if (existing == null) wasNew = true
            previousTitle = existing?.title
            previousArtist = existing?.artist
            previousOnAir = existing?.onAir ?: false
            previousBpm = existing?.effectiveBpm
            previousMaster = existing?.tempoMaster ?: false
            val updated = transform(existing ?: PlayerState.empty(deviceNumber))
                .copy(lastUpdateTimestamp = System.currentTimeMillis())
            current + (deviceNumber to updated)
        }

        val updated = _players.value.getValue(deviceNumber)
        if (wasNew) emit(AppEvent.PlayerConnected(deviceNumber))
        emit(AppEvent.PlayerStateUpdated(updated))

        if (updated.title != previousTitle || updated.artist != previousArtist) {
            emit(AppEvent.TrackChanged(deviceNumber, updated.title, updated.artist))
            emit(AppEvent.TrackLoaded(deviceNumber, updated.rekordboxTrackId))
        }
        if (updated.onAir != previousOnAir) emit(AppEvent.OnAirChanged(deviceNumber, updated.onAir))
        if (updated.effectiveBpm != null && updated.effectiveBpm != previousBpm) {
            emit(AppEvent.TempoChanged(deviceNumber, updated.effectiveBpm))
        }
        updated.playbackPositionMs?.let { emit(AppEvent.PlaybackPositionChanged(deviceNumber, it)) }

        // Recompute whenever a master-handover happens, AND whenever the
        // *current* master's own update changes its tempo/beat position -
        // otherwise pitch rides on an already-appointed master would never
        // reach MasterClockState (requirements specification §8, §12.3 "Track pitch
        // changes accurately").
        if (updated.tempoMaster != previousMaster || updated.tempoMaster) {
            recomputeMaster()
        }
    }

    fun removePlayer(deviceNumber: Int) {
        val hadMaster = _players.value[deviceNumber]?.tempoMaster == true
        _players.update { it - deviceNumber }
        emit(AppEvent.PlayerDisconnected(deviceNumber))
        if (hadMaster) recomputeMaster()
    }

    fun setNetworkAvailable(available: Boolean) {
        networkAvailable.set(available)
    }

    fun isNetworkAvailable(): Boolean = networkAvailable.get()

    /**
     * Recomputes [MasterClockState] purely from whichever player currently has
     * `tempoMaster = true`. No other heuristic is ever used (requirements specification §8).
     */
    private fun recomputeMaster() {
        val previous = _masterClock.value.masterPlayerNumber
        val master = _players.value.values.firstOrNull { it.tempoMaster }
        val newState = if (master == null || master.originalBpm == null) {
            MasterClockState.INVALID
        } else {
            MasterClockState(
                masterPlayerNumber = master.deviceNumber,
                trackBpm = master.originalBpm,
                effectiveBpm = master.effectiveBpm ?: master.originalBpm,
                beatNumber = master.beatNumber,
                beatWithinBar = master.beatWithinBar,
                lastUpdateTimestamp = System.currentTimeMillis(),
                valid = true,
            )
        }
        _masterClock.value = newState
        if (previous != newState.masterPlayerNumber) {
            logger.info { "Tempo master changed: player $previous -> ${newState.masterPlayerNumber}" }
            emit(AppEvent.MasterChanged(previous, newState.masterPlayerNumber))
        }
        emit(AppEvent.MasterClockUpdated(newState))
    }

    fun beatOccurred(deviceNumber: Int, beatNumber: Int, beatWithinBar: Int) {
        upsertPlayer(deviceNumber) { it.copy(beatNumber = beatNumber, beatWithinBar = beatWithinBar) }
        emit(AppEvent.BeatOccurred(deviceNumber, beatNumber))
        if (beatWithinBar == 1) emit(AppEvent.BarOccurred(deviceNumber))
    }

    fun reset() {
        _players.value = emptyMap()
        _masterClock.value = MasterClockState.INVALID
    }

    private fun emit(event: AppEvent) {
        if (!_events.tryEmit(event)) {
            logger.warn { "Event buffer full, dropping event: $event" }
        }
    }
}
