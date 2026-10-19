package com.beatlinkmonitor.api

import com.beatlinkmonitor.app.AppContext
import com.beatlinkmonitor.config.ApplicationConfig
import com.beatlinkmonitor.config.OutputsConfig
import com.beatlinkmonitor.config.UiProfile
import com.beatlinkmonitor.simulator.SimulatedTrack
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * The local Web-UI API (requirements specification §17.4): a WebSocket pushes the current
 * application state as JSON (Phase 4 of §38), while a small REST surface
 * handles configuration and simulator control. Binds to 127.0.0.1 by default;
 * LAN access must be explicitly enabled (requirements specification §29.1).
 */
fun startApiServer(context: AppContext): io.ktor.server.engine.ApplicationEngine {
    val host = if (context.applicationConfig.allowLanAccess) "0.0.0.0" else "127.0.0.1"
    val server = embeddedServer(Netty, port = context.applicationConfig.webUiPort, host = host) {
        apiModule(context)
    }.start(wait = false)
    logger.info { "Web UI API listening on $host:${context.applicationConfig.webUiPort}" }
    return server
}

fun Application.apiModule(context: AppContext) {
    install(WebSockets)
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    install(CallLogging) { level = Level.DEBUG }
    install(CORS) {
        anyHost()
        allowHeader(io.ktor.http.HttpHeaders.ContentType)
        allowMethod(io.ktor.http.HttpMethod.Get)
        allowMethod(io.ktor.http.HttpMethod.Post)
        allowMethod(io.ktor.http.HttpMethod.Delete)
    }

    routing {
        webSocket("/ws") {
            context.diagnostics.uiWebSocketConnections++
            logger.info { "UI WebSocket client connected (${context.diagnostics.uiWebSocketConnections} active)" }
            val json = Json { encodeDefaults = true }
            val pushJob = launch {
                combine(context.applicationState.players, context.applicationState.masterClock) { players, master ->
                    StateSnapshotMessage(
                        players = players.values.sortedBy { it.deviceNumber },
                        masterClock = master,
                        outputs = context.outputManager.statuses(),
                    )
                }.collect { snapshot ->
                    // Coalesced to ~20 Hz so waveform-adjacent fast fields stay smooth
                    // without flooding the socket (requirements specification §22).
                    send(Frame.Text(json.encodeToString(StateSnapshotMessage.serializer(), snapshot)))
                }
            }
            val diagnosticsJob = launch {
                while (isActive) {
                    send(
                        Frame.Text(
                            json.encodeToString(
                                DiagnosticsMessage.serializer(),
                                DiagnosticsMessage(diagnostics = context.diagnostics.snapshot()),
                            ),
                        ),
                    )
                    delay(1000)
                }
            }
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        // Currently read-only besides keep-alive pings; simulator/config
                        // control goes through the REST endpoints below for easier testing
                        // (e.g. via curl) without a full client implementation.
                        logger.debug { "WS message from client: ${frame.readText()}" }
                    }
                }
            } finally {
                pushJob.cancel()
                diagnosticsJob.cancel()
                context.diagnostics.uiWebSocketConnections--
                logger.info { "UI WebSocket client disconnected (${context.diagnostics.uiWebSocketConnections} active)" }
            }
        }

        get("/api/diagnostics") { call.respond(context.diagnostics.snapshot()) }

        get("/api/state") {
            call.respond(
                StateSnapshotMessage(
                    players = context.applicationState.players.value.values.sortedBy { it.deviceNumber },
                    masterClock = context.applicationState.masterClock.value,
                    outputs = context.outputManager.statuses(),
                ),
            )
        }

        get("/api/network/interfaces") {
            call.respond(com.beatlinkmonitor.prodjlink.NetworkInterfaceService.listInterfaces())
        }

        get("/api/waveform/{device}") {
            val deviceNumber = device(call)
            val player = context.applicationState.players.value[deviceNumber]
            val sampleTrackId = player?.let { if (it.source == com.beatlinkmonitor.model.PlayerSource.SIMULATED) it.rekordboxTrackId else null }
            val response = when {
                player == null -> WaveformResponse(deviceNumber, "NONE", available = false, note = "unknown player")
                com.beatlinkmonitor.waveform.SampleTrackLibrary.isSampleTrack(sampleTrackId) -> {
                    // A simulated player has loaded one of the bundled sample
                    // tracks with real, pre-analyzed waveform data - decode it
                    // via the exact same path real hardware uses (requirements specification
                    // §16.6, coding guideline #6).
                    val preview = com.beatlinkmonitor.waveform.SampleTrackLibrary.preview(sampleTrackId!!)
                    if (preview != null) {
                        val (heights, colors) = com.beatlinkmonitor.waveform.WaveformPixelDecoder.decodePreview(preview)
                        WaveformResponse(deviceNumber, player.waveformType.name, available = true, heights = heights, colors = colors)
                    } else {
                        WaveformResponse(deviceNumber, player.waveformType.name, available = false, note = "sample waveform not found")
                    }
                }
                player.source == com.beatlinkmonitor.model.PlayerSource.SIMULATED -> {
                    val seed = player.rekordboxTrackId ?: 0L
                    WaveformResponse(
                        deviceNumber = deviceNumber,
                        type = player.waveformType.name,
                        available = player.title != null,
                        heights = com.beatlinkmonitor.waveform.SimulatedWaveformGenerator.generate(seed),
                        colors = com.beatlinkmonitor.waveform.SimulatedWaveformGenerator.generateColors(seed),
                    )
                }
                else -> {
                    // Real hardware: decode the actual cached `beat-link` waveform
                    // object using its own segmentHeight/segmentColor accessors
                    // (requirements specification §9, §31) - we never invent pixel data for
                    // formats we cannot decode (coding guideline #13). Three-band
                    // waveforms use a dedicated per-band decoder (see
                    // `ThreeBandHeights`'s doc comment) instead of the regular
                    // height/color pair, since their underlying byte layout and
                    // accessor methods are entirely different from RGB/Classic.
                    val key = com.beatlinkmonitor.waveform.WaveformCacheKey(
                        (player.rekordboxTrackId ?: 0L).toInt(),
                        deviceNumber,
                        "current",
                    )
                    val cached = context.waveformCache.get(key)
                    val preview = cached?.preview
                    val detail = cached?.detail
                    when {
                        preview?.style == org.deepsymmetry.beatlink.data.WaveformFinder.WaveformStyle.THREE_BAND -> {
                            val bands = com.beatlinkmonitor.waveform.WaveformPixelDecoder.decodePreviewThreeBand(preview)
                            WaveformResponse(deviceNumber, player.waveformType.name, available = true, lowHeights = bands.low, midHeights = bands.mid, highHeights = bands.high)
                        }
                        preview != null -> {
                            val (heights, colors) = com.beatlinkmonitor.waveform.WaveformPixelDecoder.decodePreview(preview)
                            WaveformResponse(deviceNumber, player.waveformType.name, available = true, heights = heights, colors = colors)
                        }
                        detail?.style == org.deepsymmetry.beatlink.data.WaveformFinder.WaveformStyle.THREE_BAND -> {
                            val bands = com.beatlinkmonitor.waveform.WaveformPixelDecoder.decodeDetailThreeBand(detail)
                            WaveformResponse(deviceNumber, player.waveformType.name, available = true, lowHeights = bands.low, midHeights = bands.mid, highHeights = bands.high)
                        }
                        detail != null -> {
                            val (heights, colors) = com.beatlinkmonitor.waveform.WaveformPixelDecoder.decodeDetail(detail)
                            WaveformResponse(deviceNumber, player.waveformType.name, available = true, heights = heights, colors = colors)
                        }
                        else -> WaveformResponse(deviceNumber, player.waveformType.name, available = false, note = "no waveform cached yet")
                    }
                }
            }
            call.respond(response)
        }

        // Zoomed-in, scrolling "detail" waveform window around the player's
        // current playback position - used by the fullscreen Live View
        // (requirements specification §9.4). `beforeMs`/`afterMs` control how much track
        // is shown before/after the playhead, letting the frontend position
        // the playhead anywhere horizontally (not necessarily centered).
        get("/api/waveform/{device}/detail") {
            val deviceNumber = device(call)
            val beforeMs = call.request.queryParameters["beforeMs"]?.toLongOrNull() ?: 5_625L
            val afterMs = call.request.queryParameters["afterMs"]?.toLongOrNull() ?: 9_375L
            val columns = call.request.queryParameters["columns"]?.toIntOrNull() ?: 600
            val player = context.applicationState.players.value[deviceNumber]
            val positionMs = player?.playbackPositionMs
            val sampleTrackId = player?.let { if (it.source == com.beatlinkmonitor.model.PlayerSource.SIMULATED) it.rekordboxTrackId else null }
            val response = when {
                player == null -> WaveformResponse(deviceNumber, "NONE", available = false, note = "unknown player")
                positionMs == null -> WaveformResponse(deviceNumber, player.waveformType.name, available = false, note = "no playback position yet")
                com.beatlinkmonitor.waveform.SampleTrackLibrary.isSampleTrack(sampleTrackId) -> {
                    val detail = com.beatlinkmonitor.waveform.SampleTrackLibrary.detail(sampleTrackId!!)
                    if (detail != null) {
                        val (heights, colors) = com.beatlinkmonitor.waveform.WaveformPixelDecoder.decodeDetailWindow(
                            detail, positionMs, beforeMs, afterMs, columns,
                        )
                        WaveformResponse(deviceNumber, player.waveformType.name, available = true, heights = heights, colors = colors)
                    } else {
                        WaveformResponse(deviceNumber, player.waveformType.name, available = false, note = "sample waveform not found")
                    }
                }
                player.source == com.beatlinkmonitor.model.PlayerSource.SIMULATED -> {
                    val seed = player.rekordboxTrackId ?: 0L
                    val durationMs = player.trackDurationMs ?: 0L
                    val (heights, colors) = com.beatlinkmonitor.waveform.SimulatedWaveformGenerator.generateDetailWindow(
                        seed, positionMs, durationMs, beforeMs, afterMs, columns,
                    )
                    WaveformResponse(deviceNumber, player.waveformType.name, available = true, heights = heights, colors = colors)
                }
                else -> {
                    val key = com.beatlinkmonitor.waveform.WaveformCacheKey(
                        (player.rekordboxTrackId ?: 0L).toInt(),
                        deviceNumber,
                        "current",
                    )
                    val detail = context.waveformCache.get(key)?.detail
                    if (detail == null) {
                        WaveformResponse(deviceNumber, player.waveformType.name, available = false, note = "no detail waveform cached yet")
                    } else if (detail.style == org.deepsymmetry.beatlink.data.WaveformFinder.WaveformStyle.THREE_BAND) {
                        val bands = com.beatlinkmonitor.waveform.WaveformPixelDecoder.decodeDetailWindowThreeBand(
                            detail, positionMs, beforeMs, afterMs, columns,
                        )
                        WaveformResponse(deviceNumber, player.waveformType.name, available = true, lowHeights = bands.low, midHeights = bands.mid, highHeights = bands.high)
                    } else {
                        val (heights, colors) = com.beatlinkmonitor.waveform.WaveformPixelDecoder.decodeDetailWindow(
                            detail, positionMs, beforeMs, afterMs, columns,
                        )
                        WaveformResponse(deviceNumber, player.waveformType.name, available = true, heights = heights, colors = colors)
                    }
                }
            }
            call.respond(response)
        }

        // Full-track detail waveform, fetched exactly **once** per track
        // load by the Live View (requirements specification §9.4). Unlike
        // `/detail` above (a scrolling window around the current playback
        // position, meant to be re-requested as that position moves),
        // this endpoint always returns the entire track from start to end
        // regardless of where playback currently is, so the frontend can
        // hold it in memory and animate purely client-side afterwards -
        // matching how `beat-link-trigger`'s `WaveformDetailComponent` only
        // re-fetches waveform data on a track change, never on a timer
        // while playing (coding guideline #13).
        get("/api/waveform/{device}/detail/full") {
            val deviceNumber = device(call)
            val player = context.applicationState.players.value[deviceNumber]
            val sampleTrackId = player?.let { if (it.source == com.beatlinkmonitor.model.PlayerSource.SIMULATED) it.rekordboxTrackId else null }
            val durationMs = player?.trackDurationMs ?: 0L
            // Simulated (procedural, non-sample) tracks have no fixed native
            // resolution to match, so we sample densely at 150 columns/sec -
            // the same rate real Pro DJ Link detail waveforms use (150
            // half-frames/sec) - for visual parity and to avoid any
            // resampling-related flicker once the frontend scrolls through it.
            val simulatedColumns = ((durationMs / 1000.0) * 150).toInt().coerceIn(200, 100_000)
            // The real Pro DJ Link detail-waveform half-frame rate (see
            // `WaveformResponse.columnIntervalMs`'s doc comment for why this
            // must be used instead of the separately-reported, whole-seconds
            // `trackDurationMs`).
            val nativeColumnIntervalMs = 100.0 / 15.0
            val response = when {
                player == null -> WaveformResponse(deviceNumber, "NONE", available = false, note = "unknown player")
                com.beatlinkmonitor.waveform.SampleTrackLibrary.isSampleTrack(sampleTrackId) -> {
                    val detail = com.beatlinkmonitor.waveform.SampleTrackLibrary.detail(sampleTrackId!!)
                    if (detail != null) {
                        // Full native resolution (one column per half-frame,
                        // no downsampling) - this is the real, maximum-detail
                        // Pro DJ Link waveform data, not an approximation.
                        val (heights, colors) = com.beatlinkmonitor.waveform.WaveformPixelDecoder.decodeDetailFull(detail, detail.frameCount)
                        WaveformResponse(deviceNumber, player.waveformType.name, available = true, heights = heights, colors = colors, columnIntervalMs = nativeColumnIntervalMs)
                    } else {
                        WaveformResponse(deviceNumber, player.waveformType.name, available = false, note = "sample waveform not found")
                    }
                }
                player.source == com.beatlinkmonitor.model.PlayerSource.SIMULATED -> {
                    val seed = player.rekordboxTrackId ?: 0L
                    val (heights, colors) = com.beatlinkmonitor.waveform.SimulatedWaveformGenerator.generateDetailFull(seed, simulatedColumns)
                    // Self-consistent with however `simulatedColumns` was
                    // actually computed above (including the min/max
                    // clamping), rather than re-deriving the fixed 150/sec
                    // rate and risking it disagreeing with a clamped column
                    // count.
                    val columnIntervalMs = if (heights.isNotEmpty()) durationMs.toDouble() / heights.size else 0.0
                    WaveformResponse(deviceNumber, player.waveformType.name, available = player.title != null, heights = heights, colors = colors, columnIntervalMs = columnIntervalMs)
                }
                else -> {
                    val key = com.beatlinkmonitor.waveform.WaveformCacheKey(
                        (player.rekordboxTrackId ?: 0L).toInt(),
                        deviceNumber,
                        "current",
                    )
                    val detail = context.waveformCache.get(key)?.detail
                    if (detail == null) {
                        WaveformResponse(deviceNumber, player.waveformType.name, available = false, note = "no detail waveform cached yet")
                    } else if (detail.style == org.deepsymmetry.beatlink.data.WaveformFinder.WaveformStyle.THREE_BAND) {
                        // Full native resolution for real hardware too.
                        val bands = com.beatlinkmonitor.waveform.WaveformPixelDecoder.decodeDetailFullThreeBand(detail, detail.frameCount)
                        WaveformResponse(deviceNumber, player.waveformType.name, available = true, lowHeights = bands.low, midHeights = bands.mid, highHeights = bands.high, columnIntervalMs = nativeColumnIntervalMs)
                    } else {
                        // Full native resolution for real hardware too.
                        val (heights, colors) = com.beatlinkmonitor.waveform.WaveformPixelDecoder.decodeDetailFull(detail, detail.frameCount)
                        WaveformResponse(deviceNumber, player.waveformType.name, available = true, heights = heights, colors = colors, columnIntervalMs = nativeColumnIntervalMs)
                    }
                }
            }
            call.respond(response)
        }

        get("/api/waveform/{device}/beatgrid") {
            val deviceNumber = device(call)
            val player = context.applicationState.players.value[deviceNumber]
            val sampleTrackId = player?.let { if (it.source == com.beatlinkmonitor.model.PlayerSource.SIMULATED) it.rekordboxTrackId else null }
            val response = when {
                player == null -> BeatGridResponse(deviceNumber, available = false, note = "unknown player")
                com.beatlinkmonitor.waveform.SampleTrackLibrary.isSampleTrack(sampleTrackId) -> {
                    val grid = com.beatlinkmonitor.waveform.SampleTrackLibrary.beatGrid(sampleTrackId!!)
                    if (grid != null) {
                        val (times, beats) = com.beatlinkmonitor.waveform.BeatGridDecoder.decode(grid)
                        BeatGridResponse(deviceNumber, available = true, beatTimesMs = times, beatsWithinBar = beats)
                    } else {
                        BeatGridResponse(deviceNumber, available = false, note = "sample beat grid not found")
                    }
                }
                player.source == com.beatlinkmonitor.model.PlayerSource.SIMULATED -> {
                    val bpm = player.effectiveBpm ?: player.originalBpm ?: 0.0
                    val durationMs = player.trackDurationMs ?: 0L
                    val (times, beats) = com.beatlinkmonitor.waveform.SimulatedWaveformGenerator.generateBeatGrid(bpm, durationMs)
                    BeatGridResponse(deviceNumber, available = player.title != null && times.isNotEmpty(), beatTimesMs = times, beatsWithinBar = beats)
                }
                else -> {
                    val key = com.beatlinkmonitor.waveform.WaveformCacheKey(
                        (player.rekordboxTrackId ?: 0L).toInt(),
                        deviceNumber,
                        "current",
                    )
                    val grid = context.waveformCache.get(key)?.beatGrid
                    if (grid == null) {
                        BeatGridResponse(deviceNumber, available = false, note = "no beat grid cached yet")
                    } else {
                        val (times, beats) = com.beatlinkmonitor.waveform.BeatGridDecoder.decode(grid)
                        BeatGridResponse(deviceNumber, available = true, beatTimesMs = times, beatsWithinBar = beats)
                    }
                }
            }
            call.respond(response)
        }

        // --- Configuration -------------------------------------------------
        get("/api/config/application") { call.respond(context.applicationConfig) }
        post("/api/config/application") {
            val previous = context.applicationConfig
            val body = call.receive<ApplicationConfig>()
            context.configurationService.saveApplicationConfig(body)
            context.applicationConfig = body

            // Deliberately do NOT restart Pro DJ Link in-process anymore when
            // the network interface or our own virtual player number
            // changes. An earlier version of this endpoint did exactly that
            // (stop() immediately followed by start() within the same running
            // process) - this was confirmed, on a real production session, to
            // leave `beat-link`'s VirtualCdj/DeviceFinder in a state that was
            // NOT reliably equivalent to a fresh start: after several rapid
            // in-process restarts, our own virtual player ended up in an
            // active, sustained device-number claim/defense conflict with a
            // real connected CDJ, which only resolved once the application
            // was fully quit and relaunched as a new OS process (releasing
            // and rebinding the underlying UDP sockets completely). A full
            // process restart is the only restart path that has actually
            // been verified safe for these particular settings, so the user
            // is now explicitly asked to quit and reopen the application
            // instead (requirements specification §23: never risk real hardware for the
            // sake of a "live" convenience feature).
            val needsRestart = previous.networkInterfaceMode != body.networkInterfaceMode ||
                previous.manualInterfaceName != body.manualInterfaceName ||
                previous.preferredDeviceNumber != body.preferredDeviceNumber ||
                previous.waveformStylePreference != body.waveformStylePreference
            if (needsRestart) {
                logger.info { "Pro DJ Link network/hardware-communication settings changed - a full application restart is required to apply them" }
            }
            call.respond(ApplicationConfigSaveResult(restartRequired = needsRestart))
        }

        get("/api/config/outputs") { call.respond(context.configurationService.loadOutputsConfig()) }
        post("/api/config/outputs") {
            val body = call.receive<OutputsConfig>()
            context.outputManager.reloadFromConfig(body)
            call.respond(HttpStatusCode.OK)
        }

        // --- Profiles --------------------------------------------------------
        get("/api/profiles") { call.respond(context.configurationService.listProfiles()) }
        get("/api/profiles/active") {
            call.respond(context.configurationService.loadProfile(context.applicationConfig.activeProfile))
        }
        post("/api/profiles/active/{name}") {
            val name = call.parameters["name"]!!
            context.applicationConfig = context.applicationConfig.copy(activeProfile = name)
            context.configurationService.saveApplicationConfig(context.applicationConfig)
            call.respond(context.configurationService.loadProfile(name))
        }
        get("/api/profiles/{name}") {
            val name = call.parameters["name"]!!
            call.respond(context.configurationService.loadProfile(name))
        }
        post("/api/profiles") {
            val profile = call.receive<UiProfile>()
            context.configurationService.saveProfile(profile)
            call.respond(HttpStatusCode.OK)
        }
        delete("/api/profiles/{name}") {
            val name = call.parameters["name"]!!
            context.configurationService.deleteProfile(name)
            call.respond(HttpStatusCode.OK)
        }
        get("/api/profiles/{name}/export") {
            val name = call.parameters["name"]!!
            val tmp = File.createTempFile("profile-$name", ".json")
            context.configurationService.exportProfile(name, tmp)
            call.respond(tmp.readText())
        }
        post("/api/profiles/import") {
            val text = call.receive<String>()
            val tmp = File.createTempFile("import", ".json")
            tmp.writeText(text)
            val profile = context.configurationService.importProfile(tmp)
            call.respond(profile)
        }

        // --- Simulator -------------------------------------------------------
        post("/api/simulator/start") {
            context.simulationService.start()
            context.diagnostics.simulatorRunning = true
            call.respond(HttpStatusCode.OK)
        }
        post("/api/simulator/stop") {
            context.simulationService.stop()
            context.diagnostics.simulatorRunning = false
            call.respond(HttpStatusCode.OK)
        }
        post("/api/simulator/{device}/connect") {
            context.simulationService.connect(device(call))
            call.respond(HttpStatusCode.OK)
        }
        post("/api/simulator/{device}/disconnect") {
            context.simulationService.disconnect(device(call))
            call.respond(HttpStatusCode.OK)
        }
        post("/api/simulator/{device}/play") {
            context.simulationService.setPlaying(device(call), true)
            call.respond(HttpStatusCode.OK)
        }
        post("/api/simulator/{device}/pause") {
            context.simulationService.setPlaying(device(call), false)
            call.respond(HttpStatusCode.OK)
        }
        post("/api/simulator/{device}/onair") {
            val body = call.receive<ClientCommand>()
            context.simulationService.setOnAir(device(call), body.boolValue ?: true)
            call.respond(HttpStatusCode.OK)
        }
        post("/api/simulator/{device}/master") {
            context.simulationService.setMaster(device(call))
            call.respond(HttpStatusCode.OK)
        }
        post("/api/simulator/{device}/pitch") {
            val body = call.receive<ClientCommand>()
            context.simulationService.setPitch(device(call), body.value ?: 0.0)
            call.respond(HttpStatusCode.OK)
        }
        post("/api/simulator/{device}/seek") {
            val body = call.receive<ClientCommand>()
            context.simulationService.seek(device(call), body.positionMs ?: 0L)
            call.respond(HttpStatusCode.OK)
        }
        post("/api/simulator/{device}/load/{trackIndex}") {
            val track: SimulatedTrack = com.beatlinkmonitor.simulator.SimulationService.DEMO_TRACKS
                .getOrElse(call.parameters["trackIndex"]!!.toInt()) { com.beatlinkmonitor.simulator.SimulationService.DEMO_TRACKS[0] }
            context.simulationService.loadTrack(device(call), track)
            call.respond(HttpStatusCode.OK)
        }
        get("/api/simulator/tracks") { call.respond(com.beatlinkmonitor.simulator.SimulationService.DEMO_TRACKS) }

        // --- GrandMA2 test -----------------------------------------------------
        post("/api/outputs/grandma/test") {
            val body = call.receive<ClientCommand>()
            withContext(Dispatchers.IO) { context.outputManager.grandMa.sendTest(body.value ?: 140.0) }
            call.respond(HttpStatusCode.OK)
        }

        // --- GrandMA2 Telnet console (dedicated "MA Telnet" tab) ----------------
        get("/api/outputs/grandma/console") {
            call.respond(context.outputManager.grandMa.getConsoleHistory())
        }
        post("/api/outputs/grandma/send") {
            val body = call.receive<TelnetSendCommand>()
            withContext(Dispatchers.IO) { context.outputManager.grandMa.sendUserCommand(body.command) }
            call.respond(HttpStatusCode.OK)
        }
        webSocket("/ws/telnet-console") {
            // New lines sent after this connection opens stream live; the
            // full backlog up to this point is fetched once via the plain
            // REST endpoint above when the tab first opens, avoiding any
            // ambiguity about which lines were already shown.
            val json = Json { encodeDefaults = true }
            try {
                context.outputManager.grandMa.consoleLines.collect { line ->
                    send(Frame.Text(json.encodeToString(com.beatlinkmonitor.outputs.grandma.TelnetConsoleLine.serializer(), line)))
                }
            } catch (e: Exception) {
                logger.debug { "Telnet console WebSocket closed: ${e.message}" }
            }
        }

        get("/health") { call.respond(mapOf("status" to "ok")) }

        // --- Embedded Web UI -------------------------------------------------
        // Serves the pre-built Svelte frontend (frontend/dist, copied into
        // src/main/resources/webapp by the `copyFrontend` Gradle task) from
        // the same port as the API/WebSocket, so the whole application is a
        // single process with a single port - required for the native
        // desktop launcher (DesktopApp) to show one self-contained window,
        // and for a packaged .app/.exe to "just work" with no separate
        // frontend server to start.
        staticResources("/", "webapp") {
            default("index.html")
        }
    }
}

private fun device(call: ApplicationCall): Int = call.parameters["device"]!!.toInt()
