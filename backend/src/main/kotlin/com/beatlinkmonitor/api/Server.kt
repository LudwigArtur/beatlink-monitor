package com.beatlinkmonitor.api

import com.beatlinkmonitor.app.AppContext
import com.beatlinkmonitor.config.ApplicationConfig
import com.beatlinkmonitor.config.OutputsConfig
import com.beatlinkmonitor.config.UiProfile
import com.beatlinkmonitor.cdj.CdjProtocol
import com.beatlinkmonitor.cdj.RemoteUnlockStatus
import com.beatlinkmonitor.cdj.SetPinStatus
import com.beatlinkmonitor.mixer.MixerSnapshot
import com.beatlinkmonitor.mixer.UtilityWriteRequest
import com.beatlinkmonitor.mixer.withModel
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
 * handles configuration and mixer control. Binds to 127.0.0.1 by default;
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
        allowHeader(CONNECTION_HEADER)
        allowMethod(io.ktor.http.HttpMethod.Get)
        allowMethod(io.ktor.http.HttpMethod.Post)
        allowMethod(io.ktor.http.HttpMethod.Delete)
    }

    routing {
        webSocket("/ws") {
            val connectionId = newConnectionId()
            context.remoteUnlock.connectionOpened(connectionId)
            // First message, so that a client knows its connection id before any state arrives.
            runCatching { send(Frame.Text("""{"type":"hello","connectionId":"$connectionId"}""")) }
            context.diagnostics.uiWebSocketConnections++
            logger.info { "UI WebSocket client connected (${context.diagnostics.uiWebSocketConnections} active)" }
            val json = Json { encodeDefaults = true }
            val pushJob = launch {
                combine(
                    context.applicationState.players,
                    context.applicationState.masterClock,
                    context.applicationState.mixer,
                ) { players, master, mixer ->
                    StateSnapshotMessage(
                        players = players.values.sortedBy { it.deviceNumber },
                        masterClock = master,
                        outputs = context.outputManager.statuses(),
                        mixer = mixer.withModel(players.values),
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
                        // Currently read-only besides keep-alive pings; mixer/config
                        // control goes through the REST endpoints below for easier testing
                        // (e.g. via curl) without a full client implementation.
                        logger.debug { "WS message from client: ${frame.readText()}" }
                    }
                }
            } finally {
                context.remoteUnlock.connectionClosed(connectionId)
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
                    mixer = context.applicationState.mixer.value.withModel(context.applicationState.players.value.values),
                ),
            )
        }

        get("/api/network/interfaces") {
            call.respond(com.beatlinkmonitor.prodjlink.NetworkInterfaceService.listInterfaces())
        }

        get("/api/waveform/{device}") {
            val deviceNumber = device(call)
            val player = context.applicationState.players.value[deviceNumber]
            val response = when {
                player == null -> WaveformResponse(deviceNumber, "NONE", available = false, note = "unknown player")
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
            val response = when {
                player == null -> WaveformResponse(deviceNumber, "NONE", available = false, note = "unknown player")
                positionMs == null -> WaveformResponse(deviceNumber, player.waveformType.name, available = false, note = "no playback position yet")
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
            val durationMs = player?.trackDurationMs ?: 0L
            // The real Pro DJ Link detail-waveform half-frame rate (see
            // `WaveformResponse.columnIntervalMs`'s doc comment for why this
            // must be used instead of the separately-reported, whole-seconds
            // `trackDurationMs`).
            val nativeColumnIntervalMs = 100.0 / 15.0
            val response = when {
                player == null -> WaveformResponse(deviceNumber, "NONE", available = false, note = "unknown player")
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
            val response = when {
                player == null -> BeatGridResponse(deviceNumber, available = false, note = "unknown player")
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
            // Network visibility decides who can reach the PIN-protected hardware writes, so only the device the
            // server runs on may change it, and turning it on needs a freshly created PIN (see RemoteUnlockService).
            if (body.allowLanAccess != previous.allowLanAccess && !isLocalClient(call)) {
                call.respond(HttpStatusCode.Forbidden, "LAN access can only be changed on the device the server runs on")
                return@post
            }
            if (body.allowLanAccess && !previous.allowLanAccess && !context.remoteUnlock.isPinSet()) {
                call.respond(HttpStatusCode.Conflict, "Create a PIN before turning on LAN access")
                return@post
            }
            context.configurationService.saveApplicationConfig(body)
            context.applicationConfig = body
            if (!body.allowLanAccess) context.remoteUnlock.clearPin()

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
                previous.waveformStylePreference != body.waveformStylePreference ||
                previous.mixerIntegrationEnabled != body.mixerIntegrationEnabled ||
                previous.allowLanAccess != body.allowLanAccess
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

        // --- Mixer -------------------------------------------------------------
        get("/api/mixer") {
            call.respond(MixerSnapshot(context.applicationState.mixer.value.withModel(context.applicationState.players.value.values), context.mixerLink.status()))
        }
        get("/api/mixer/levels") { call.respond(context.audioLevels.status()) }
        post("/api/mixer/utility/read") {
            call.respond(mapOf("started" to context.mixerLink.requestReadAll()))
        }
        post("/api/mixer/utility/{id}/read") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest)
            } else {
                call.respond(mapOf("sent" to context.mixerLink.requestRead(id)))
            }
        }
        post("/api/mixer/utility/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest)
            } else if (!isTrustedOrigin(call)) {
                call.respond(HttpStatusCode.Forbidden, "Cross-origin mixer writes are not allowed")
            } else if (!hardwareWriteAllowed(context, call)) {
                call.respond(HttpStatusCode.Forbidden, PIN_REQUIRED_MESSAGE)
            } else {
                val body = call.receive<UtilityWriteRequest>()
                call.respond(context.mixerLink.writeUtility(id, body.value))
            }
        }
        // --- CDJs: settings, deck flags, remote buttons (see Docs/CDJ_CONTROL.md) ----
        get("/api/cdj") { call.respond(context.cdjLink.snapshot()) }
        post("/api/cdj/refresh") {
            context.cdjLink.requestRefresh()
            call.respond(HttpStatusCode.Accepted)
        }
        post("/api/cdj/{player}/setting/{offset}") {
            val player = call.parameters["player"]?.toIntOrNull()
            val offset = call.parameters["offset"]?.toIntOrNull()
            if (player == null || offset == null) {
                call.respond(HttpStatusCode.BadRequest)
            } else if (!isTrustedOrigin(call)) {
                call.respond(HttpStatusCode.Forbidden, "Cross-origin CDJ writes are not allowed")
            } else if (!hardwareWriteAllowed(context, call)) {
                call.respond(HttpStatusCode.Forbidden, PIN_REQUIRED_MESSAGE)
            } else {
                val body = call.receive<UtilityWriteRequest>()
                call.respond(context.cdjLink.writeSetting(player, offset, body.value))
            }
        }
        // Writes to DJMs and CDJs are unlocked per WebSocket connection with the PIN created when LAN access was turned on.
        // The device the server runs on needs no PIN; only it may create or change the PIN.
        get("/api/cdj/remote") {
            call.respond(
                context.remoteUnlock.info(call.request.headers[CONNECTION_HEADER], isLocalClient(call), context.applicationConfig.allowLanAccess),
            )
        }
        post("/api/cdj/remote/pin") {
            if (!isLocalClient(call)) {
                call.respond(HttpStatusCode.Forbidden, "Only the device the server runs on can change the PIN")
            } else {
                val result = context.remoteUnlock.setPin(call.receive<RemoteUnlockRequest>().pin)
                val code = when (result.status) {
                    SetPinStatus.SAVED -> HttpStatusCode.OK
                    SetPinStatus.INVALID -> HttpStatusCode.BadRequest
                    SetPinStatus.FAILED -> HttpStatusCode.InternalServerError
                }
                call.respond(code, result)
            }
        }
        post("/api/cdj/remote/unlock") {
            if (!isTrustedOrigin(call)) {
                call.respond(HttpStatusCode.Forbidden)
            } else {
                val body = call.receive<RemoteUnlockRequest>()
                val result = context.remoteUnlock.unlock(call.request.headers[CONNECTION_HEADER], body.pin, call.request.local.remoteHost)
                val code = when (result.status) {
                    RemoteUnlockStatus.UNLOCKED -> HttpStatusCode.OK
                    RemoteUnlockStatus.LOCKED_OUT -> HttpStatusCode.TooManyRequests
                    RemoteUnlockStatus.WRONG_PIN, RemoteUnlockStatus.UNKNOWN_CONNECTION -> HttpStatusCode.Forbidden
                    else -> HttpStatusCode.Conflict
                }
                call.respond(code, result)
            }
        }
        post("/api/cdj/remote/lock") {
            context.remoteUnlock.lock(call.request.headers[CONNECTION_HEADER])
            call.respond(HttpStatusCode.OK)
        }
        post("/api/cdj/{player}/button/{code}") {
            val player = call.parameters["player"]?.toIntOrNull()
            val code = call.parameters["code"]?.toIntOrNull()
            val press = when (call.request.queryParameters["press"]?.lowercase()) {
                null, "", "click" -> CdjProtocol.Press.CLICK
                "down" -> CdjProtocol.Press.DOWN
                "up" -> CdjProtocol.Press.UP
                else -> null
            }
            if (player == null || code == null || press == null) {
                call.respond(HttpStatusCode.BadRequest)
            } else if (!isTrustedOrigin(call)) {
                call.respond(HttpStatusCode.Forbidden, "Cross-origin CDJ control is not allowed")
            } else if (!hardwareWriteAllowed(context, call)) {
                call.respond(HttpStatusCode.Forbidden, PIN_REQUIRED_MESSAGE)
            } else {
                call.respond(context.cdjLink.pressButton(player, code, press))
            }
        }
        // Diagnostic: record the traffic on the Pro DJ Link status socket into a pcap file (see PacketRecorder).
        get("/api/mixer/record") { call.respond(context.packetRecorder.status()) }
        post("/api/mixer/record/start") {
            if (!isTrustedOrigin(call)) {
                call.respond(HttpStatusCode.Forbidden)
            } else if (!context.mixerTransport.available) {
                call.respond(HttpStatusCode.Conflict, "The Pro DJ Link status socket is not hooked (enable the mixer integration)")
            } else {
                context.packetRecorder.start()
                call.respond(context.packetRecorder.status())
            }
        }
        post("/api/mixer/record/stop") { call.respond(context.packetRecorder.stop()) }

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
        post("/api/outputs/grandma/reconnect") {
            if (!isTrustedOrigin(call)) {
                call.respond(HttpStatusCode.Forbidden)
            } else {
                context.outputManager.grandMa.reconnectNow()
                call.respond(HttpStatusCode.Accepted)
            }
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

private const val CONNECTION_HEADER = "X-Connection-Id"

@kotlinx.serialization.Serializable
private data class RemoteUnlockRequest(val pin: String)

private const val PIN_REQUIRED_MESSAGE = "Writing to mixers and CDJs from this device is locked: enter the PIN first"

/** Writes to a DJM or CDJ: the server device always, every other device only after it entered the PIN. */
private fun hardwareWriteAllowed(context: AppContext, call: ApplicationCall): Boolean =
    context.remoteUnlock.isUnlocked(call.request.headers[CONNECTION_HEADER], isLocalClient(call))

/** See [LocalClient]; a cross-origin browser page never counts as the local device. */
private fun isLocalClient(call: ApplicationCall): Boolean =
    isTrustedOrigin(call) &&
        LocalClient.isLocal(call.request.local.remoteAddress, call.request.headers["Host"], call.request.headers.names())

private fun newConnectionId(): String {
    val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun device(call: ApplicationCall): Int = call.parameters["device"]!!.toInt()

/**
 * Mixer writes change real hardware settings, so a browser page from another
 * origin must not be able to trigger them just because the API allows
 * cross-origin requests: a request with an Origin header is only accepted if
 * it comes from this server itself or from the Vite dev server.
 */
private fun isTrustedOrigin(call: ApplicationCall): Boolean {
    val origin = call.request.headers["Origin"] ?: return true
    val originAuthority = origin.substringAfter("://", "")
    val hostHeader = call.request.headers["Host"]
    return originAuthority == hostHeader || originAuthority in setOf("localhost:5173", "127.0.0.1:5173")
}
