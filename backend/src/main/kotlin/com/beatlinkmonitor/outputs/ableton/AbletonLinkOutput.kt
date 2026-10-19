package com.beatlinkmonitor.outputs.ableton

import com.beatlinkmonitor.config.AbletonLinkConfig
import com.beatlinkmonitor.model.MasterClockState
import com.beatlinkmonitor.outputs.OutputModule
import com.beatlinkmonitor.outputs.OutputStatus
import com.beatlinkmonitor.state.ApplicationState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

/** Locale-independent decimal formatting - the Carabiner protocol requires a
 * period decimal point regardless of the host machine's default locale
 * (a German/European locale would otherwise send "128,0000" and be rejected
 * with `bad-bpm`). */
internal fun fmt(value: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", value)

/**
 * Bridges the Pro DJ Link tempo master to Ableton Link via a locally running
 * Carabiner process (requirements specification §12). This module owns a dedicated timing
 * loop (requirements specification §19 Executor C / §12.3): it is never driven by UI
 * render ticks, tolerates brief packet loss, and reacts to master hand-overs
 * and pitch changes independently of anything the UI is doing.
 *
 * Both tempo *and* beat/bar phase are synchronized (requirements specification §12.1
 * explicitly requires more than tempo alone): [maybeSendTempo] keeps Link's
 * BPM following the master's effective tempo, while [maybeAlignPhase] keeps
 * Link's bar boundary aligned to the master's downbeat using the configured
 * quantum.
 */
class AbletonLinkOutput(
    private val applicationState: ApplicationState,
    private var config: AbletonLinkConfig,
) : OutputModule {

    override val name: String = "Ableton Link"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: CarabinerClient? = null
    private var loopJob: Job? = null
    private var lastSentBpm: Double? = null
    private var lastMasterPlayer: Int? = null
    private var lastAlignedBeatNumber: Int? = null
    private var lastPhaseAlignAt: Long = 0L
    private val status = AtomicReference(
        OutputStatus(name = name, enabled = config.enabled, connected = false, detail = "peers=0"),
    )

    companion object {
        private const val BPM_EPSILON = 0.005 // avoid re-sending on floating point noise
        private const val POLL_INTERVAL_MS = 50L // independent of UI FPS (requirements specification §12.3, §22)
        private const val PERIODIC_REALIGN_INTERVAL_MS = 8_000L // re-correct drift without constant phase jumps
    }

    fun updateConfig(newConfig: AbletonLinkConfig) {
        val wasEnabled = config.enabled
        config = newConfig
        status.set(status.get().copy(enabled = newConfig.enabled))
        if (newConfig.enabled && !wasEnabled) start()
        if (!newConfig.enabled && wasEnabled) stop()
    }

    override fun start() {
        if (!config.enabled) return
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { runLoop() }
        logger.info { "Ableton Link output starting (carabiner=${config.carabinerHost}:${config.carabinerPort})" }
    }

    override fun stop() {
        loopJob?.cancel()
        loopJob = null
        client?.close()
        client = null
        status.set(status.get().copy(connected = false))
        logger.info { "Ableton Link output stopped" }
    }

    override fun getStatus(): OutputStatus = status.get()

    private suspend fun runLoop() {
        while (scope.coroutineContext.isActive) {
            try {
                ensureConnected()
                val c = client
                if (c != null && c.isConnected()) {
                    c.tryReadStatus()?.let { s ->
                        status.set(
                            status.get().copy(
                                connected = true,
                                detail = "peers=${s.peers} linkBpm=${fmt(s.bpm, 2)}",
                            ),
                        )
                    }
                    maybeSendTempo(c, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                status.set(status.get().copy(connected = false, lastError = e.message))
                logger.warn { "Ableton Link / Carabiner error: ${e.message}" }
                client?.close()
                client = null
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun ensureConnected() {
        val c = client
        if (c != null && c.isConnected()) return
        val bindAddress = com.beatlinkmonitor.prodjlink.NetworkInterfaceService.resolveBindAddress(
            config.networkInterfaceMode,
            config.manualInterfaceName,
        )
        val fresh = CarabinerClient(config.carabinerHost, config.carabinerPort, bindAddress)
        fresh.connect()
        fresh.send("status")
        client = fresh
        status.set(status.get().copy(connected = true, lastError = null))
        logger.info { "Connected to Carabiner at ${config.carabinerHost}:${config.carabinerPort}" }
    }

    private suspend fun maybeSendTempo(client: CarabinerClient, now: Long) {
        val master: MasterClockState = applicationState.masterClock.value
        if (!master.valid || master.effectiveBpm == null) return

        val bpm = master.effectiveBpm.coerceIn(20.0, 999.0)
        val bpmChanged = lastSentBpm == null || abs(bpm - lastSentBpm!!) >= BPM_EPSILON
        val masterHandoff = master.masterPlayerNumber != lastMasterPlayer

        if (bpmChanged || masterHandoff) {
            client.send("bpm ${fmt(bpm, 4)}")
            lastSentBpm = bpm
            status.set(status.get().copy(lastSentAt = now, lastSentPayload = "bpm=${fmt(bpm, 2)}"))
        }

        if (masterHandoff) {
            // A new Pro DJ Link master took over: track it so the next bpm change
            // (or this one) is sent immediately, keeping Link's tempo following
            // the new master without waiting on UI refresh rates
            // (requirements specification §12.4, §16.5, §39.2 Scenario C).
            lastMasterPlayer = master.masterPlayerNumber
            lastAlignedBeatNumber = null // force an immediate phase realignment below
            logger.info { "Ableton Link following new Pro DJ Link master: player ${master.masterPlayerNumber}" }
        }

        maybeAlignPhase(client, master, now, forceOnHandoff = masterHandoff)
    }

    /**
     * Aligns Ableton Link's beat/bar *phase* to the Pro DJ Link master's
     * current position within its bar - not just its tempo (requirements specification
     * §12.1: "Not just tempo should be transmitted": tempo, beat phase and
     * bar phase/quantum).
     *
     * Realignment only ever happens right on a downbeat (`beatWithinBar == 1`)
     * so the correction lands on a musically meaningful boundary instead of
     * yanking Link's timeline mid-bar, and at most once per distinct beat
     * number so the 50ms poll loop does not resend it repeatedly while the
     * master holds at beat 1 (requirements specification §12.3: jitter must be minimized).
     */
    private suspend fun maybeAlignPhase(
        client: CarabinerClient,
        master: MasterClockState,
        now: Long,
        forceOnHandoff: Boolean,
    ) {
        val beatWithinBar = master.beatWithinBar ?: return
        val beatNumber = master.beatNumber ?: return
        if (beatWithinBar != 1) return
        if (!forceOnHandoff && beatNumber == lastAlignedBeatNumber) return
        if (!forceOnHandoff && now - lastPhaseAlignAt < PERIODIC_REALIGN_INTERVAL_MS) return

        val quantum = config.quantum.coerceAtLeast(1.0)
        val nowMicros = System.nanoTime() / 1000
        // Beat 1 of the bar is the downbeat: align Link's phase so its own
        // beat counter is at a quantum boundary (0.0) at this exact moment.
        client.send("force-beat-at-time 0.0 $nowMicros ${fmt(quantum, 4)}")
        lastAlignedBeatNumber = beatNumber
        lastPhaseAlignAt = now
        logger.debug { "Aligned Ableton Link phase to Pro DJ Link downbeat (beat #$beatNumber, quantum=$quantum)" }
    }
}
