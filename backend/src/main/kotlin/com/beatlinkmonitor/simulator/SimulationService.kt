package com.beatlinkmonitor.simulator

import com.beatlinkmonitor.model.BpmMath
import com.beatlinkmonitor.model.DeviceType
import com.beatlinkmonitor.model.PlayerSource
import com.beatlinkmonitor.model.WaveformType
import com.beatlinkmonitor.state.ApplicationState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

private val logger = KotlinLogging.logger {}

@kotlinx.serialization.Serializable
data class SimulatedTrack(
    val title: String,
    val artist: String,
    val trackId: Long,
    val originalBpm: Double,
    val durationMs: Long,
)

/**
 * A virtual player state, independent of any real hardware, that feeds
 * exactly the same [ApplicationState] mutation methods that
 * [com.beatlinkmonitor.prodjlink.ProDjLinkAdapter] uses (requirements specification §16,
 * coding guideline #6: "Connect the simulator through the same interfaces
 * as real hardware").
 */
class SimulatedPlayer(val deviceNumber: Int) {
    var connected: Boolean = false
    var playing: Boolean = false
    var onAir: Boolean = false
    var master: Boolean = false
    var pitchPercent: Double = 0.0
    var track: SimulatedTrack? = null
    var positionMs: Long = 0L
    var beatNumber: Int = 1
    var beatWithinBar: Int = 1
}

/**
 * Drives up to six [SimulatedPlayer]s so the whole application - UI, GrandMA2
 * output, Ableton Link - can be developed and tested without any Pro DJ Link
 * hardware attached (requirements specification §16, a mandatory component).
 */
class SimulationService(private val state: ApplicationState) {

    private val players = (1..6).associateWith { SimulatedPlayer(it) }
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private const val TICK_MS = 40L // ~25 Hz internal tick, sufficient for smooth position/beat updates

        val DEMO_TRACKS = listOf(
            SimulatedTrack("Strobe", "Deadmau5", 1001L, 128.0, 10 * 60 * 1000L),
            SimulatedTrack("One More Time", "Daft Punk", 1002L, 123.0, 5 * 60 * 1000L + 20_000),
            SimulatedTrack("Language", "Porter Robinson", 1003L, 128.0, 4 * 60 * 1000L + 45_000),
            SimulatedTrack("Opus", "Eric Prydz", 1004L, 126.0, 9 * 60 * 1000L),
        ) + com.beatlinkmonitor.waveform.SampleTrackLibrary.TRACKS.map {
            // Real, pre-analyzed waveform data (requirements specification §16.6) - see
            // SampleTrackLibrary and src/main/resources/sampletracks/README.md.
            SimulatedTrack(it.title, it.artist, it.trackId, it.originalBpm, it.durationMs)
        }
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            logger.info { "Simulation service started" }
            var lastTick = System.currentTimeMillis()
            while (isActive) {
                kotlinx.coroutines.delay(TICK_MS)
                val now = System.currentTimeMillis()
                val elapsed = now - lastTick
                lastTick = now
                players.values.forEach { advance(it, elapsed) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        players.keys.forEach { state.removePlayer(it) }
        logger.info { "Simulation service stopped" }
    }

    fun connect(deviceNumber: Int, name: String = "Simulated CDJ-$deviceNumber") {
        val player = players.getValue(deviceNumber)
        player.connected = true
        publish(player, deviceName = name)
        logger.info { "Simulator: player $deviceNumber connected" }
    }

    fun disconnect(deviceNumber: Int) {
        val player = players.getValue(deviceNumber)
        player.connected = false
        player.playing = false
        player.master = false
        state.removePlayer(deviceNumber)
        logger.info { "Simulator: player $deviceNumber disconnected" }
    }

    fun reconnect(deviceNumber: Int) = connect(deviceNumber)

    fun setPlaying(deviceNumber: Int, playing: Boolean) {
        players.getValue(deviceNumber).playing = playing
        publish(players.getValue(deviceNumber))
    }

    fun setPaused(deviceNumber: Int, paused: Boolean) = setPlaying(deviceNumber, !paused)

    fun setOnAir(deviceNumber: Int, onAir: Boolean) {
        players.getValue(deviceNumber).onAir = onAir
        publish(players.getValue(deviceNumber))
    }

    /** Appoints [deviceNumber] as the sole Pro DJ Link tempo master, per requirements specification §16.5. */
    fun setMaster(deviceNumber: Int) {
        val previous = players.values.firstOrNull { it.master }?.deviceNumber
        players.values.forEach { it.master = (it.deviceNumber == deviceNumber) }
        players.values.forEach { publish(it) }
        logger.info { "Simulator: master handover $previous -> $deviceNumber" }
    }

    fun setPitch(deviceNumber: Int, percent: Double) {
        players.getValue(deviceNumber).pitchPercent = percent.coerceIn(-10.0, 10.0)
        publish(players.getValue(deviceNumber))
    }

    fun loadTrack(deviceNumber: Int, track: SimulatedTrack) {
        val player = players.getValue(deviceNumber)
        player.track = track
        player.positionMs = 0
        player.beatNumber = 1
        player.beatWithinBar = 1
        publish(player)
        logger.info { "Simulator: player $deviceNumber loaded '${track.title}' by ${track.artist}" }
    }

    fun seek(deviceNumber: Int, positionMs: Long) {
        val player = players.getValue(deviceNumber)
        val duration = player.track?.durationMs ?: return
        player.positionMs = positionMs.coerceIn(0, duration)
        publish(player)
    }

    fun snapshot(): List<SimulatedPlayer> = players.values.toList()

    private fun advance(player: SimulatedPlayer, elapsedMs: Long) {
        if (!player.connected || !player.playing) return
        val track = player.track ?: return
        val effectiveBpm = BpmMath.effectiveBpm(track.originalBpm, player.pitchPercent)
        // Pitch changes playback *speed* on real hardware (vinyl-style, not
        // time-stretched), so track position must advance faster/slower
        // with it too - not just the beat counter. This also keeps the
        // frontend's client-side position extrapolation (used for smooth
        // Live View scrolling) correct for both real and simulated players,
        // since both then advance position at the same `1 + pitch/100` rate.
        val playbackRate = 1.0 + player.pitchPercent / 100.0
        player.positionMs = min(player.positionMs + (elapsedMs * playbackRate).toLong(), track.durationMs)

        val msPerBeat = 60_000.0 / effectiveBpm
        val beatsElapsed = (elapsedMs / msPerBeat)
        if (beatsElapsed >= 1.0) {
            val steps = beatsElapsed.toInt()
            repeat(max(1, steps)) {
                player.beatWithinBar = (player.beatWithinBar % 4) + 1
                player.beatNumber += 1
                state.beatOccurred(player.deviceNumber, player.beatNumber, player.beatWithinBar)
            }
        }
        if (player.positionMs >= track.durationMs) {
            player.playing = false
        }
        publish(player)
    }

    private fun publish(player: SimulatedPlayer, deviceName: String? = null) {
        if (!player.connected) return
        val track = player.track
        val effectiveBpm = track?.let { BpmMath.effectiveBpm(it.originalBpm, player.pitchPercent) }
        state.upsertPlayer(player.deviceNumber) { existing ->
            existing.copy(
                deviceName = deviceName ?: existing.deviceName.ifBlank { "Simulated CDJ-${player.deviceNumber}" },
                deviceType = DeviceType.CDJ,
                connected = true,
                playing = player.playing,
                paused = !player.playing && track != null,
                onAir = player.onAir,
                tempoMaster = player.master,
                rekordboxTrackId = track?.trackId,
                title = track?.title,
                artist = track?.artist,
                originalBpm = track?.originalBpm,
                pitchPercent = player.pitchPercent,
                effectiveBpm = effectiveBpm,
                beatNumber = player.beatNumber,
                beatWithinBar = player.beatWithinBar,
                playbackPositionMs = player.positionMs,
                trackDurationMs = track?.durationMs,
                waveformType = if (track != null) WaveformType.RGB else WaveformType.NONE,
                hasWaveformPreview = track != null,
                hasWaveformDetail = track != null,
                hasBeatGrid = track != null,
                source = PlayerSource.SIMULATED,
            )
        }
    }
}
