package com.beatlinkmonitor.prodjlink

import com.beatlinkmonitor.model.BpmMath
import com.beatlinkmonitor.model.DeviceType
import com.beatlinkmonitor.model.PlayerSource
import com.beatlinkmonitor.model.WaveformType
import com.beatlinkmonitor.state.ApplicationState
import com.beatlinkmonitor.waveform.CachedWaveform
import com.beatlinkmonitor.waveform.WaveformCache
import com.beatlinkmonitor.waveform.WaveformCacheKey
import io.github.oshai.kotlinlogging.KotlinLogging
import org.deepsymmetry.beatlink.Beat
import org.deepsymmetry.beatlink.BeatFinder
import org.deepsymmetry.beatlink.CdjStatus
import org.deepsymmetry.beatlink.DeviceAnnouncement
import org.deepsymmetry.beatlink.DeviceAnnouncementListener
import org.deepsymmetry.beatlink.DeviceFinder
import org.deepsymmetry.beatlink.DeviceUpdate
import org.deepsymmetry.beatlink.DeviceUpdateListener
import org.deepsymmetry.beatlink.MixerStatus
import org.deepsymmetry.beatlink.VirtualCdj
import org.deepsymmetry.beatlink.data.BeatGridFinder
import org.deepsymmetry.beatlink.data.MetadataFinder
import org.deepsymmetry.beatlink.data.TimeFinder
import org.deepsymmetry.beatlink.data.WaveformFinder

private val logger = KotlinLogging.logger {}

/**
 * Bridges the real Pro DJ Link network (via Deep Symmetry's `beat-link`) into
 * [ApplicationState]. This is the *only* place in the backend allowed to talk
 * to `beat-link` directly (requirements specification §20, coding guideline #5).
 *
 * Each `beat-link` subsystem (discovery, master/beat tracking, metadata,
 * waveforms, beat grids, precise timing) is started independently and
 * failures are isolated per subsystem, so that e.g. a metadata lookup failure
 * never brings down device discovery or the rest of the application
 * (requirements specification §23).
 */
class ProDjLinkAdapter(
    private val state: ApplicationState,
    private val waveformCache: WaveformCache,
) {
    private val deviceFinder = DeviceFinder.getInstance()
    private val virtualCdj = VirtualCdj.getInstance()
    private val beatFinder = BeatFinder.getInstance()
    private val metadataFinder = MetadataFinder.getInstance()
    private val waveformFinder = WaveformFinder.getInstance()
    private val beatGridFinder = BeatGridFinder.getInstance()
    private val timeFinder = TimeFinder.getInstance()

    /** Addresses we ourselves added to [deviceFinder]'s ignore list, so a later restart/mode switch can clean up after itself. */
    private val selfIgnoredAddresses = mutableSetOf<java.net.InetAddress>()

    @Volatile
    private var started = false

    // --- Metadata/waveform self-healing watchdog ------------------------
    //
    // Observed on real hardware (Raspberry Pi deployment, long-running
    // sessions): beat-link's shared `ConnectionManager` (used internally by
    // `MetadataFinder`/`WaveformFinder`/`BeatGridFinder` to fetch track
    // metadata, waveforms and beat grids from each CDJ's dbserver TCP port)
    // can silently wedge itself after extended uptime - title/track-ID/
    // waveform/beat-grid then never arrive for *any* player again, with
    // nothing at all logged (no exception, no warning), while `VirtualCdj`/
    // `TimeFinder`'s UDP-broadcast-derived data (BPM, pitch, beat, position,
    // tempo master, on-air, loop) keeps working perfectly fine throughout -
    // these are independent subsystems, and only the dbserver/TCP-based one
    // gets stuck. (A previous, narrower instance of what looks like the same
    // underlying `ConnectionManager` fragility is already noted in
    // `start()`'s `WaveformFinder preferred style` comment below.)
    //
    // Since this is a silent failure with no detectable trigger to fix at
    // the root (deep inside a third-party library, with no corresponding
    // upstream issue/fix known at the time of writing), this watchdog
    // instead detects the *symptom* - a player that clearly has a track
    // loaded (per the raw, UDP-broadcast-derived `originalBpm`, which does
    // not depend on the dbserver/metadata path at all) but has had no
    // title/track-ID for longer than any real metadata fetch should ever
    // take - and recovers by restarting just the affected subsystems
    // (Metadata/Waveform/BeatGridFinder + the shared ConnectionManager),
    // leaving the confirmed-healthy VirtualCdj/BeatFinder/TimeFinder
    // completely untouched (requirements specification §23: isolate failures, recover
    // automatically wherever possible rather than require a manual restart
    // mid-event).
    private val metadataWatchdogStuckSince = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /** The waveform style [start] was called with, remembered so [restartMetadataStack] can reapply it after recovery. */
    @Volatile
    private var waveformStylePreference: String = "RGB"

    @Volatile
    private var lastMetadataStackRestartAt: Long = 0

    @Volatile
    private var metadataWatchdogThread: Thread? = null

    private companion object {
        /** How long a player may show a loaded track without any metadata before we consider the metadata pipeline stuck. */
        const val METADATA_STUCK_THRESHOLD_MS = 90_000L

        /** Minimum time between automatic metadata-stack restarts, so a still-recovering stack is not endlessly re-kicked. */
        const val METADATA_RESTART_COOLDOWN_MS = 3 * 60_000L

        /** Minimum time after (re)starting before the very first automatic recovery restart may happen, win or lose. */
        const val METADATA_INITIAL_GRACE_MS = 60_000L

        /** How often the watchdog re-evaluates player state. */
        const val METADATA_WATCHDOG_INTERVAL_MS = 20_000L
    }

    private val deviceAnnouncementListener = object : DeviceAnnouncementListener {
        override fun deviceFound(announcement: DeviceAnnouncement) {
            logger.info { "Device connected: #${announcement.deviceNumber} '${announcement.deviceName}' @ ${announcement.address.hostAddress}" }
            state.upsertPlayer(announcement.deviceNumber) { player ->
                player.copy(
                    connected = true,
                    deviceName = announcement.deviceName,
                    ipAddress = announcement.address.hostAddress,
                    source = PlayerSource.REAL,
                )
            }
        }

        override fun deviceLost(announcement: DeviceAnnouncement) {
            logger.info { "Device disconnected: #${announcement.deviceNumber} '${announcement.deviceName}'" }
            waveformCache.evictForPlayer(announcement.deviceNumber)
            state.removePlayer(announcement.deviceNumber)
        }
    }

    private val deviceUpdateListener = DeviceUpdateListener { update -> handleUpdate(update) }

    private val beatListener = org.deepsymmetry.beatlink.BeatListener { beat: Beat ->
        state.beatOccurred(beat.deviceNumber, deriveBeatNumber(beat.deviceNumber), beat.beatWithinBar)
    }

    init {
        metadataFinder.addTrackMetadataListener { update ->
            val metadata = update.metadata
            state.upsertPlayer(update.player) { player ->
                player.copy(
                    title = metadata?.title,
                    artist = metadata?.artist?.label,
                    rekordboxTrackId = metadata?.trackReference?.rekordboxId?.toLong(),
                    trackDurationMs = metadata?.duration?.toLong()?.times(1000),
                )
            }
        }
        waveformFinder.addWaveformListener(object : org.deepsymmetry.beatlink.data.WaveformListener {
            override fun previewChanged(update: org.deepsymmetry.beatlink.data.WaveformPreviewUpdate) {
                cacheAndApply(update.player, preview = update.preview, detail = null, style = update.preview?.style)
            }

            override fun detailChanged(update: org.deepsymmetry.beatlink.data.WaveformDetailUpdate) {
                cacheAndApply(update.player, preview = null, detail = update.detail, style = update.detail?.style)
            }
        })
        beatGridFinder.addBeatGridListener { update ->
            state.upsertPlayer(update.player) { it.copy(hasBeatGrid = update.beatGrid != null) }
            if (update.beatGrid != null) {
                cacheAndApply(update.player, preview = null, detail = null, style = null, beatGrid = update.beatGrid)
            }
        }
    }

    private fun cacheAndApply(
        player: Int,
        preview: org.deepsymmetry.beatlink.data.WaveformPreview?,
        detail: org.deepsymmetry.beatlink.data.WaveformDetail?,
        style: org.deepsymmetry.beatlink.data.WaveformFinder.WaveformStyle?,
        beatGrid: org.deepsymmetry.beatlink.data.BeatGrid? = null,
    ) {
        // Derive the track id directly from whichever beat-link object this
        // event actually belongs to (its own `dataReference.rekordboxId`),
        // rather than from `ApplicationState`'s current `rekordboxTrackId`
        // field. That field is populated independently - and often *later*
        // - by `MetadataFinder`, so relying on it here was a race condition:
        // if a waveform/beat-grid event for a newly loaded track arrived
        // before its metadata did, it got cached under the *previous*
        // track's id (or 0 if none yet), and the UI's lookup - which always
        // uses the by-then-current, correct id - would then miss the cache
        // entirely. This was the root cause of waveforms intermittently
        // "just not appearing at all" on real hardware, where network
        // timing between the independent WaveformFinder/BeatGridFinder and
        // MetadataFinder subsystems is not guaranteed to preserve any
        // particular order.
        val trackId = (preview?.dataReference ?: detail?.dataReference ?: beatGrid?.dataReference)?.rekordboxId
            ?: (state.players.value[player]?.rekordboxTrackId?.toInt() ?: 0)
        val key = WaveformCacheKey(trackId, player, "current")
        val existing = waveformCache.get(key)
        // Reads the actual style beat-link reports for *this specific*
        // waveform object (not just a plain color/monochrome flag) - needed
        // to tell a genuine CDJ-3000 three-band waveform apart from a
        // regular NXS2-style RGB one, both of which report `isColor = true`.
        val type = when (style) {
            org.deepsymmetry.beatlink.data.WaveformFinder.WaveformStyle.THREE_BAND -> WaveformType.THREE_BAND
            org.deepsymmetry.beatlink.data.WaveformFinder.WaveformStyle.RGB -> WaveformType.RGB
            org.deepsymmetry.beatlink.data.WaveformFinder.WaveformStyle.BLUE -> WaveformType.CLASSIC
            null -> existing?.type ?: WaveformType.NONE
        }
        waveformCache.put(
            key,
            CachedWaveform(
                type = type,
                preview = preview ?: existing?.preview,
                detail = detail ?: existing?.detail,
                beatGrid = beatGrid ?: existing?.beatGrid,
            ),
        )
        state.upsertPlayer(player) {
            it.copy(
                waveformType = type,
                hasWaveformPreview = preview != null || existing?.preview != null,
                hasWaveformDetail = detail != null || existing?.detail != null,
            )
        }
    }

    private fun deriveBeatNumber(deviceNumber: Int): Int =
        state.players.value[deviceNumber]?.beatNumber?.plus(1) ?: 1

    private fun handleUpdate(update: DeviceUpdate) {
        val deviceNumber = update.deviceNumber
        val type = when (update) {
            is CdjStatus -> DeviceType.CDJ
            is MixerStatus -> DeviceType.MIXER
            else -> DeviceType.UNKNOWN
        }

        // Precise, beat-grid-interpolated playback position (requirements specification
        // §9.4 / §22): real `DeviceUpdate`s do not carry an absolute
        // millisecond position field themselves - `TimeFinder` derives it
        // from the device's beat/tempo/pitch state plus its cached beat
        // grid, exactly like `beat-link-trigger`'s own player status
        // display. Returns a negative value while this cannot yet be
        // determined (e.g. no beat grid cached yet for a just-loaded
        // track), in which case the previous known-good position is kept
        // rather than being reset to zero/null, so the UI's playhead does
        // not visibly snap backwards for a frame.
        //
        // (A `TimeFinder.addTrackPositionListener()`-based approach was
        // tried here instead, to sidestep a theorized listener-ordering
        // race with this same `getTimeFor()` call - but that listener only
        // fires on *significant, unexpected* corrections, not continuously,
        // and reverting to a per-packet `getTimeFor()` call turned out to
        // be necessary: the listener-based version left the position
        // frozen almost entirely, confirmed directly on real hardware. Kept
        // as the simpler, working approach; the residual jitter this was
        // meant to address is apparently inherent to the underlying
        // precise-position-packet data itself, not this read pattern.)
        val positionMs = timeFinder.getTimeFor(deviceNumber).takeIf { it >= 0 }

        state.upsertPlayer(deviceNumber) { player ->
            // 65535 is the CDJ's sentinel for "no track loaded" (beat-link CdjStatus#getBpm).
            val originalBpm = if (update.bpm in 1..65534) update.bpm / 100.0 else player.originalBpm
            val pitchPercent = org.deepsymmetry.beatlink.Util.pitchToPercentage(update.pitch.toLong())
            val effectiveBpm = originalBpm?.let { BpmMath.effectiveBpm(it, pitchPercent) }

            var updated = player.copy(
                deviceType = type,
                connected = true,
                tempoMaster = update.isTempoMaster,
                pitchPercent = pitchPercent,
                originalBpm = originalBpm,
                effectiveBpm = effectiveBpm ?: update.effectiveTempo,
                beatWithinBar = if (update.isBeatWithinBarMeaningful) update.beatWithinBar else player.beatWithinBar,
                playbackPositionMs = positionMs ?: player.playbackPositionMs,
                source = PlayerSource.REAL,
            )

            if (update is CdjStatus) {
                val nowLooping = update.playState1 == CdjStatus.PlayState1.LOOPING
                var loopStartMs = player.loopStartMs
                var loopEndMs = player.loopEndMs
                if (!nowLooping) {
                    // Loop ended (or was never active) - clear any bounds
                    // from a previous loop so they don't linger into a
                    // later, different loop.
                    loopStartMs = null
                    loopEndMs = null
                } else if (player.looping && positionMs != null && player.playbackPositionMs != null) {
                    // Still looping as of the previous update too: detect
                    // the loop *wrapping back to its start*, which is the
                    // only way the continuously-reported playback position
                    // can ever jump backward by more than ordinary
                    // interpolation jitter (already established elsewhere
                    // to be at most a few tens of milliseconds) while
                    // actively playing forward. The position just before
                    // the jump is the loop's end/out point; just after is
                    // its start/in point - this measures the real loop
                    // boundaries directly from observed hardware behavior,
                    // rather than guessing them (coding guideline #13).
                    val delta = positionMs - player.playbackPositionMs
                    if (delta < -150L) {
                        loopEndMs = player.playbackPositionMs
                        loopStartMs = positionMs
                    }
                }
                updated = updated.copy(
                    playing = update.isPlaying,
                    paused = update.isPaused,
                    onAir = update.isOnAir,
                    looping = nowLooping,
                    loopStartMs = loopStartMs,
                    loopEndMs = loopEndMs,
                    beatNumber = if (update.beatNumber > 0) update.beatNumber else updated.beatNumber,
                )
            } else if (type == DeviceType.MIXER) {
                // Mixers never report track metadata/playback state over Pro DJ
                // Link; mark these fields explicitly unsupported rather than
                // leaving them ambiguous or inventing values (requirements specification §3.2,
                // §6, coding guideline #13).
                updated = updated.copy(
                    dataAvailability = updated.dataAvailability + mapOf(
                        "title" to com.beatlinkmonitor.model.Availability.UNSUPPORTED,
                        "artist" to com.beatlinkmonitor.model.Availability.UNSUPPORTED,
                        "playing" to com.beatlinkmonitor.model.Availability.UNSUPPORTED,
                        "onAir" to com.beatlinkmonitor.model.Availability.UNSUPPORTED,
                        "waveform" to com.beatlinkmonitor.model.Availability.UNSUPPORTED,
                    ),
                )
            }
            updated
        }
    }

    /**
     * Starts every `beat-link` subsystem. Each one is isolated in its own
     * try/catch so a single failure (e.g. no matching network interface yet)
     * never prevents the rest of the application - including the simulator
     * and the web UI - from working (requirements specification §23).
     *
     * When [manualInterfaceName] is given (requirements specification §28.1), every other
     * local network interface's addresses are added to `beat-link`'s ignore
     * list *before* discovery starts, so Pro DJ Link traffic seen on other
     * adapters (e.g. a Wi-Fi uplink alongside a dedicated USB-Ethernet link,
     * per §28.2) is not picked up.
     *
     * [preferredDeviceNumber] lets the user explicitly pin our own virtual
     * CDJ's device number (1-15) instead of relying on automatic
     * self-assignment - 0 (the default) means "automatic". This must be set
     * on `VirtualCdj` *before* `start()` is called (beat-link throws if
     * changed while running). It is purely a *preference*: the standard
     * Pro DJ Link number-claim protocol (handled entirely inside beat-link)
     * still defends against a real conflict even if this number happens to
     * already be in use - the UI is additionally expected to prevent
     * choosing an already-occupied number in the first place (see
     * `/api/prodjlink/occupied-device-numbers`), but this is defense in
     * depth, not the only safeguard.
     *
     * [waveformStylePreference] ("RGB" or "THREE_BAND") controls which
     * waveform style is requested from real CDJ-3000s - see
     * `ApplicationConfig.waveformStylePreference`'s doc comment.
     */
    fun start(manualInterfaceName: String? = null, preferredDeviceNumber: Int = 0, waveformStylePreference: String = "RGB") {
        if (started) return
        started = true
        this.waveformStylePreference = waveformStylePreference

        clearSelfIgnoredAddresses()
        if (manualInterfaceName != null) {
            restrictToInterface(manualInterfaceName)
        }

        deviceFinder.addDeviceAnnouncementListener(deviceAnnouncementListener)
        virtualCdj.addUpdateListener(deviceUpdateListener)
        beatFinder.addBeatListener(beatListener)

        // Prefer a "standard" device number (1-4) for our own virtual CDJ if
        // one happens to be free, instead of beat-link's conservative
        // default of always using 7-15. This matters a great deal in
        // practice: `beat-link`'s metadata/waveform/beat-grid requests must
        // present themselves to a target player as coming from a *different*
        // standard (1-4) player number (a Pioneer dbserver protocol
        // requirement). If our own number is outside 1-4 (the default), it
        // can only proceed by "borrowing" another real CDJ's number for the
        // duration of the request - which fails outright whenever that real
        // CDJ happens to be Link-synced to the very track being queried, and
        // can fail *entirely* if fewer than two real players currently sit
        // in the 1-4 range. This exact failure
        // ("No player number available to query player N ... we can't steal
        // its channel number") was confirmed directly from a real session's
        // log file and explained every reported symptom: metadata/waveforms
        // intermittently never appearing for already-loaded tracks, only
        // "sometimes" recovering once a new track load happened to retry
        // the request under different (successful) conditions.
        //
        // This setting is always safe to enable: per beat-link's own
        // documented fallback behavior, if all of 1-4 are genuinely already
        // claimed by real hardware, self-assignment transparently falls
        // back to 7-15 exactly as before - there is no "stealing" or
        // conflict risk, only a chance at a better-behaved number when one
        // happens to be free (requirements specification §20, §23: never worse than the
        // previous behavior, only ever strictly better when possible).
        virtualCdj.useStandardPlayerNumber = true

        // A user-pinned device number always takes priority over automatic
        // self-assignment above - must be applied before VirtualCdj.start()
        // (it throws IllegalStateException if changed while running).
        if (preferredDeviceNumber in 1..15) {
            virtualCdj.deviceNumber = preferredDeviceNumber.toByte()
            logger.info { "Using user-selected virtual player number $preferredDeviceNumber" }
        }

        // NOTE: beat-link 8.0.0 added `TimeFinder.setUsePrecisePositionPackets(false)`,
        // documented upstream as a workaround for jitter when synchronizing
        // with *Ableton Link* specifically. We tried disabling it here on
        // the assumption it would also help the Live View's waveform
        // smoothness, but real-hardware testing showed the opposite: with
        // it disabled, TimeFinder only gets a fresh position anchor once per
        // *beat* (via plain beat packets) instead of continuously via the
        // CDJ-3000's own high-frequency precise-position stream, so the
        // client-side extrapolation between anchors has to cover a much
        // larger gap and any per-beat clock-rate mismatch shows up as a
        // visible periodic jerk - worse than the original fine-grained
        // jitter it was meant to fix. Deliberately left at beat-link's own
        // default (enabled) for the UI's sake; Ableton Link's own BPM sync
        // (a separate, much coarser signal) is unaffected either way.

        safeStart("DeviceFinder") { deviceFinder.start() }
        safeStart("VirtualCdj") { virtualCdj.start() }
        safeStart("BeatFinder") { beatFinder.start() }
        safeStart("MetadataFinder") { metadataFinder.start() }
        safeStart("WaveformFinder") { waveformFinder.start() }
        safeStart("BeatGridFinder") { beatGridFinder.start() }
        safeStart("TimeFinder") { timeFinder.start() }

        // Request the user's preferred waveform style - "THREE_BAND" asks
        // for the CDJ-3000's native, highest-detail per-frequency-band
        // style (beat-link 8.0.0+) instead of the older, more universally
        // compatible NXS2-style RGB waveform (beat-link's own default).
        // Hardware that doesn't support the requested style transparently
        // falls back to whatever it *can* provide, per `WaveformFinder`'s
        // own documented fallback behavior - so this is always safe to set
        // even on a mixed rig, regardless of which style is chosen. Set
        // *after* `waveformFinder.start()` (unlike the device-number/
        // interface settings above, which require the opposite order) -
        // beat-link reads this preference fresh at actual request time, so
        // there is no need to set it earlier, and doing so before the
        // finder's own worker threads/state exist was suspected to have
        // interacted badly with the shared `ConnectionManager` that
        // `MetadataFinder` also depends on (observed once, on real
        // hardware, as metadata/title/track-ID silently never arriving for
        // any player at all, with no errors logged at all - consistent
        // with every metadata request silently stalling rather than
        // failing loudly).
        safeStart("WaveformFinder preferred style") {
            waveformFinder.preferredStyle = when (waveformStylePreference) {
                "THREE_BAND" -> org.deepsymmetry.beatlink.data.WaveformFinder.WaveformStyle.THREE_BAND
                else -> org.deepsymmetry.beatlink.data.WaveformFinder.WaveformStyle.RGB
            }
        }

        logger.info { "Pro DJ Link adapter started (running=${isRunning()}, ownDeviceNumber=${virtualCdj.deviceNumber})" }

        startMetadataWatchdog()
    }

    fun stop() {
        started = false
        stopMetadataWatchdog()
        safeStop("TimeFinder") { timeFinder.stop() }
        safeStop("BeatGridFinder") { beatGridFinder.stop() }
        safeStop("WaveformFinder") { waveformFinder.stop() }
        safeStop("MetadataFinder") { metadataFinder.stop() }
        safeStop("BeatFinder") { beatFinder.stop() }
        safeStop("VirtualCdj") { virtualCdj.stop() }
        safeStop("DeviceFinder") { deviceFinder.stop() }
        deviceFinder.removeDeviceAnnouncementListener(deviceAnnouncementListener)
        virtualCdj.removeUpdateListener(deviceUpdateListener)
        beatFinder.removeBeatListener(beatListener)
        logger.info { "Pro DJ Link adapter stopped" }
    }

    private fun startMetadataWatchdog() {
        metadataWatchdogStuckSince.clear()
        // Start the cooldown clock just short of fully elapsed (rather than
        // at "now"), so a metadata pipeline that is stuck from the very
        // first update after this start/restart doesn't have to wait a full
        // extra cooldown period on top of the stuck-detection threshold
        // before its first automatic recovery attempt - while still never
        // restarting before METADATA_INITIAL_GRACE_MS have passed, to give
        // a normal/healthy startup's slightly-slower-than-usual first fetch
        // room to complete on its own.
        lastMetadataStackRestartAt = System.currentTimeMillis() - (METADATA_RESTART_COOLDOWN_MS - METADATA_INITIAL_GRACE_MS)
        val thread = Thread({
            while (started) {
                try {
                    Thread.sleep(METADATA_WATCHDOG_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                }
                if (!started) break
                runCatching { checkMetadataHealth() }
                    .onFailure { logger.warn(it) { "Metadata watchdog check failed" } }
            }
        }, "metadata-watchdog").apply { isDaemon = true }
        metadataWatchdogThread = thread
        thread.start()
    }

    private fun stopMetadataWatchdog() {
        metadataWatchdogThread?.interrupt()
        metadataWatchdogThread = null
        metadataWatchdogStuckSince.clear()
    }

    /**
     * Looks for players that clearly have a track loaded (per the raw
     * UDP-broadcast-derived [com.beatlinkmonitor.model.PlayerState.originalBpm],
     * which is independent of the dbserver/metadata path) but have had no
     * title/track-ID for longer than [METADATA_STUCK_THRESHOLD_MS] - see the
     * watchdog fields' doc comment above for why this indicates the shared
     * `ConnectionManager` has wedged - and recovers by restarting the
     * metadata/waveform/beat-grid subsystems if so, respecting
     * [METADATA_RESTART_COOLDOWN_MS] between attempts.
     */
    private fun checkMetadataHealth() {
        val now = System.currentTimeMillis()
        val currentlyStuck = state.players.value.values
            .filter { it.connected && it.source == PlayerSource.REAL && it.originalBpm != null && it.title == null && it.rekordboxTrackId == null }
            .map { it.deviceNumber }
            .toSet()

        // Forget players that recovered (got metadata) or disconnected/unloaded since the last check.
        metadataWatchdogStuckSince.keys.retainAll(currentlyStuck)

        currentlyStuck.forEach { deviceNumber ->
            metadataWatchdogStuckSince.putIfAbsent(deviceNumber, now)
        }

        val stuckTooLong = metadataWatchdogStuckSince.any { (_, since) -> now - since >= METADATA_STUCK_THRESHOLD_MS }
        if (!stuckTooLong) return

        if (now - lastMetadataStackRestartAt < METADATA_RESTART_COOLDOWN_MS) {
            logger.debug { "Metadata watchdog: stuck player(s) detected but still within restart cooldown" }
            return
        }

        logger.warn {
            "Metadata watchdog: player(s) ${metadataWatchdogStuckSince.keys.sorted()} have had a loaded track but no metadata for " +
                "${METADATA_STUCK_THRESHOLD_MS / 1000}s+ - restarting metadata/waveform/beat-grid subsystems to recover"
        }
        restartMetadataStack()
        lastMetadataStackRestartAt = now
        metadataWatchdogStuckSince.clear()
    }

    /**
     * Restarts the dbserver-dependent subsystems (Metadata/Waveform/
     * BeatGridFinder and the `ConnectionManager` they share), without
     * touching `DeviceFinder`/`VirtualCdj`/`BeatFinder`, which stay healthy
     * throughout this specific failure mode (see the watchdog fields' doc
     * comment above) and must keep tracking BPM/master/on-air/loop
     * uninterrupted while this recovery happens.
     *
     * `TimeFinder` *must* also be explicitly restarted here, even though it
     * is not itself part of the dbserver/metadata pipeline this watchdog
     * targets: beat-link's `TimeFinder.start()` registers itself as a
     * `LifecycleListener` on `BeatGridFinder` (among others) and - entirely
     * undocumented in its own public API - automatically, silently stops
     * *itself* the moment `BeatGridFinder` stops (`TimeFinder`'s internal
     * `lifecycleListener.stopped()`, which calls its own `stop()`).
     * Discovered the hard way on the Raspberry Pi deployment: the very
     * first time this watchdog fired and stopped/restarted `BeatGridFinder`
     * above, `TimeFinder` silently stopped as an undocumented side effect
     * and was *never* restarted - permanently freezing that player's
     * playback position (`getTimeFor()` then returns -1 forever, and our
     * own code in `handleUpdate()` keeps the last known position rather
     * than resetting it, so the UI's playhead and waveform scroll position
     * froze solid) while BPM/beat/master tracking (driven directly by
     * `VirtualCdj`/`BeatFinder`, unaffected by this) kept working fine -
     * exactly the "late waveform, barely-twitching playhead" symptom this
     * fixes. Restarting it here re-establishes its listeners against the
     * freshly restarted `BeatGridFinder` and resumes live position tracking.
     */
    private fun restartMetadataStack() {
        safeStop("TimeFinder") { timeFinder.stop() }
        safeStop("BeatGridFinder") { beatGridFinder.stop() }
        safeStop("WaveformFinder") { waveformFinder.stop() }
        safeStop("MetadataFinder") { metadataFinder.stop() }
        safeStop("ConnectionManager") { org.deepsymmetry.beatlink.dbserver.ConnectionManager.getInstance().stop() }

        safeStart("MetadataFinder") { metadataFinder.start() }
        safeStart("WaveformFinder") { waveformFinder.start() }
        safeStart("BeatGridFinder") { beatGridFinder.start() }
        safeStart("TimeFinder") { timeFinder.start() }
        safeStart("WaveformFinder preferred style") {
            waveformFinder.preferredStyle = when (waveformStylePreference) {
                "THREE_BAND" -> org.deepsymmetry.beatlink.data.WaveformFinder.WaveformStyle.THREE_BAND
                else -> org.deepsymmetry.beatlink.data.WaveformFinder.WaveformStyle.RGB
            }
        }
        logger.info { "Metadata watchdog: metadata/waveform/beat-grid/time-tracking subsystems restarted" }
    }

    fun isRunning(): Boolean = deviceFinder.isRunning || virtualCdj.isRunning

    /**
     * The device number beat-link's VirtualCdj ended up self-assigning on the network (or null if not running
     * yet). With [useStandardPlayerNumber] enabled, beat-link dynamically scans which numbers are actually in
     * use on the network (whatever combination of real players is present) and claims the lowest free one,
     * defending it against collisions per the standard Pro DJ Link number-claim protocol - so no manual
     * configuration is required to avoid conflicts, regardless of which four of 1-6 the real players occupy.
     */
    fun ownDeviceNumber(): Int? = if (virtualCdj.isRunning) virtualCdj.deviceNumber.toInt() else null

    private fun clearSelfIgnoredAddresses() {
        selfIgnoredAddresses.forEach { deviceFinder.removeIgnoredAddress(it) }
        selfIgnoredAddresses.clear()
    }

    private fun restrictToInterface(manualInterfaceName: String) {
        try {
            val allInterfaces = java.net.NetworkInterface.getNetworkInterfaces().toList()
            val selected = allInterfaces.firstOrNull { it.name == manualInterfaceName }
            if (selected == null) {
                logger.warn { "Configured network interface '$manualInterfaceName' not found - falling back to automatic detection" }
                return
            }
            var ignoredCount = 0
            allInterfaces
                .filter { it.name != manualInterfaceName }
                .flatMap { it.inetAddresses.toList() }
                .forEach { address ->
                    deviceFinder.addIgnoredAddress(address)
                    selfIgnoredAddresses.add(address)
                    ignoredCount++
                }
            logger.info { "Restricted Pro DJ Link discovery to interface '$manualInterfaceName', ignoring $ignoredCount other address(es) (requirements specification §28.1)" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to restrict Pro DJ Link discovery to interface '$manualInterfaceName' - falling back to automatic detection" }
        }
    }

    private inline fun safeStart(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logger.error(e) { "Failed to start Pro DJ Link subsystem '$name' - continuing without it" }
        }
    }

    private inline fun safeStop(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logger.warn(e) { "Error while stopping Pro DJ Link subsystem '$name'" }
        }
    }
}
