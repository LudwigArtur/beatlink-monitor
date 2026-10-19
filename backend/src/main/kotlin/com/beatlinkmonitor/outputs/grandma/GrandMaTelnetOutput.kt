package com.beatlinkmonitor.outputs.grandma

import com.beatlinkmonitor.config.GrandMaConfig
import com.beatlinkmonitor.model.MasterClockState
import com.beatlinkmonitor.outputs.OutputModule
import com.beatlinkmonitor.outputs.OutputStatus
import com.beatlinkmonitor.outputs.RateLimiter
import com.beatlinkmonitor.state.ApplicationState
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/** One line of Telnet console traffic, for the dedicated "MA Telnet" console UI (requirements specification §13). */
@Serializable
data class TelnetConsoleLine(
    val timestamp: Long,
    val direction: String, // SENT | RECEIVED | INFO
    val text: String,
)

/**
 * Sends the current Pro DJ Link master BPM to a grandMA2 console over its
 * Telnet access point (requirements specification §13). Runs entirely on its own
 * coroutine/executor (requirements specification §19, Executor D) so a stalled or
 * unreachable console never blocks Pro DJ Link processing, Ableton Link, or
 * the UI (requirements specification §23).
 *
 * Also behaves like a real Telnet console: every line sent *or* received is
 * recorded (bounded history) and exposed live, so the UI's dedicated "MA
 * Telnet" tab can show the actual back-and-forth traffic and let the user
 * type arbitrary ad-hoc commands, not just the automated BPM updates.
 */
class GrandMaTelnetOutput(
    private val applicationState: ApplicationState,
    private var config: GrandMaConfig,
) : OutputModule {

    override val name: String = "GrandMA2 Telnet"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socketJob: Job? = null
    private var readerJob: Job? = null
    private var watchJob: Job? = null
    private val socketMutex = Mutex()
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private val rateLimiter = RateLimiter(config.sendOnChange, config.minimumChange, config.maximumRateHz)
    private val status = AtomicReference(OutputStatus(name = name, enabled = config.enabled, connected = false))

    // Bounded console history (requirements specification: dedicated MA Telnet tab shows
    // the console) - capped so a long session's automated BPM-update
    // traffic can't grow this without bound. `Collections.synchronizedList`
    // because both the writer (main `sendRaw` callers) and the dedicated
    // reader coroutine append to it concurrently.
    private val consoleHistory = Collections.synchronizedList(ArrayList<TelnetConsoleLine>())
    private val maxHistoryLines = 1000

    // Live stream of newly-appended lines for the WebSocket console view -
    // a *new* subscriber still gets the full `consoleHistory` backlog
    // separately (see `getConsoleHistory()`), this is only for lines added
    // *after* a client has already loaded that backlog.
    private val _consoleLines = MutableSharedFlow<TelnetConsoleLine>(extraBufferCapacity = 64)
    val consoleLines = _consoleLines.asSharedFlow()

    // Separate from `consoleLines` (which is for the UI's display only):
    // lets `connect()` wait for specific lines from the console (its
    // initial login banner, then our own login's confirmation) without
    // competing with the UI's own subscription. A small replay buffer
    // covers the unavoidable race between the reader coroutine starting and
    // this flow gaining its first waiting collector.
    private val _receivedLines = MutableSharedFlow<String>(replay = 10, extraBufferCapacity = 64)

    fun getConsoleHistory(): List<TelnetConsoleLine> = consoleHistory.toList()

    private fun recordConsoleLine(direction: String, text: String) {
        val line = TelnetConsoleLine(System.currentTimeMillis(), direction, text)
        consoleHistory.add(line)
        while (consoleHistory.size > maxHistoryLines) consoleHistory.removeAt(0)
        _consoleLines.tryEmit(line)
    }

    fun updateConfig(newConfig: GrandMaConfig) {
        val wasEnabled = config.enabled
        config = newConfig
        rateLimiter.configure(newConfig.sendOnChange, newConfig.minimumChange, newConfig.maximumRateHz)
        status.set(status.get().copy(enabled = newConfig.enabled))
        if (newConfig.enabled && !wasEnabled) start()
        if (!newConfig.enabled && wasEnabled) stop()
    }

    override fun start() {
        if (!config.enabled) return
        if (socketJob?.isActive == true) return
        socketJob = scope.launch { connectionLoop() }
        watchJob = applicationState.masterClock
            .onEach { onMasterChanged(it) }
            .launchIn(scope)
        logger.info { "GrandMA2 Telnet output starting (host=${config.host}:${config.port})" }
    }

    override fun stop() {
        socketJob?.cancel()
        readerJob?.cancel()
        watchJob?.cancel()
        closeSocket()
        status.set(status.get().copy(connected = false))
        logger.info { "GrandMA2 Telnet output stopped" }
    }

    override fun getStatus(): OutputStatus = status.get()

    /** Sends an ad-hoc test command without requiring any Pro DJ Link hardware (requirements specification §13.7). */
    suspend fun sendTest(bpm: Double = 140.0) {
        val fakeMaster = MasterClockState(masterPlayerNumber = 0, trackBpm = bpm, effectiveBpm = bpm, valid = true)
        sendCommand(CommandTemplateEngine.render(config.commandTemplate, fakeMaster), force = true)
    }

    /**
     * Sends an arbitrary, user-typed command from the dedicated MA Telnet
     * console tab - distinct from the automated BPM-update flow, but using
     * the exact same connection/socket.
     */
    suspend fun sendUserCommand(command: String) {
        if (command.isBlank()) return
        try {
            sendRaw(command)
        } catch (e: Exception) {
            recordConsoleLine("INFO", "Error sending command: ${e.message}")
            logger.warn { "Failed to send user MA Telnet command: ${e.message}" }
        }
    }

    private suspend fun connectionLoop() {
        while (scope.isActive) {
            try {
                connect()
                // Stay connected until the socket drops or a send fails elsewhere
                // (tracked via the shared `connected` status flag), sending
                // keepalives if requested.
                while (scope.isActive && status.get().connected && socket?.isClosed == false) {
                    if (config.keepalive) {
                        sendRaw("\r\n") // benign no-op keepalive
                    }
                    delay(2_000)
                }
            } catch (e: Exception) {
                status.set(status.get().copy(connected = false, lastError = e.message))
                recordConsoleLine("INFO", "Connection lost/failed: ${e.message}")
                logger.warn { "GrandMA2 Telnet connection lost/failed: ${e.message}" }
            }
            closeSocket()
            if (!config.reconnect) break
            delay(3_000)
        }
    }

    private suspend fun connect() {
        socketMutex.withLock {
            logger.info { "Connecting to GrandMA2 Telnet at ${config.host}:${config.port}" }
            recordConsoleLine("INFO", "Connecting to ${config.host}:${config.port}...")
            val s = Socket()
            // Bind to the configured outbound network adapter, if one was
            // explicitly selected - independent of whichever adapter Pro DJ
            // Link discovery uses, since a console reachable from a
            // different NIC than the CDJs is a realistic multi-adapter
            // setup (requirements specification).
            val bindAddress = com.beatlinkmonitor.prodjlink.NetworkInterfaceService.resolveBindAddress(
                config.networkInterfaceMode,
                config.manualInterfaceName,
            )
            if (bindAddress != null) {
                s.bind(java.net.InetSocketAddress(bindAddress, 0))
            }
            s.connect(java.net.InetSocketAddress(config.host, config.port), 5_000)
            s.soTimeout = 2_000
            socket = s
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.US_ASCII))
            reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
            status.set(status.get().copy(connected = true, lastError = null))
            recordConsoleLine("INFO", "Connected.")
        }
        readerJob?.cancel()
        readerJob = scope.launch { readLoop() }

        if (config.username.isNotEmpty() || config.password.isNotEmpty()) {
            // The console auto-logs in as its own default/guest user the
            // instant a Telnet client connects - entirely on its own
            // initiative, independent of anything we send - and announces
            // this with a line containing "Logged in" (e.g. "Logged in as
            // User 'guest'"). Sending our own login command *before* that
            // banner has arrived was observed to make the console simply
            // drop the connection rather than process it, presumably
            // because it had not yet finished its own connection-setup
            // handshake. Waiting for it first makes the login reliable.
            val sawInitialBanner = withTimeoutOrNull(5_000) {
                _receivedLines.first { it.contains("Logged in", ignoreCase = true) }
            } != null
            if (!sawInitialBanner) {
                recordConsoleLine("INFO", "No initial login banner seen within 5s - sending login anyway.")
            }

            val login = CommandTemplateEngine.render(
                config.loginTemplate,
                MasterClockState.INVALID,
                username = config.username,
                password = config.password,
            )
            sendRaw(login)
            logger.info { "GrandMA2 login sent (${CommandTemplateEngine.redactForLogging(login, config.password)})" }

            // Confirm the login actually succeeded (a reply containing both
            // "Logged in" and our own username, e.g. "Logged in as User
            // 'remote'") before treating BPM sync as safe to start - then
            // immediately send the *current* BPM once, rather than waiting
            // for it to happen to change first (requirements specification: the console
            // should reflect the real tempo right away after (re)connecting,
            // not just from the next tempo change onward).
            val confirmedUser = withTimeoutOrNull(5_000) {
                _receivedLines.first { it.contains("Logged in", ignoreCase = true) && it.contains(config.username, ignoreCase = true) }
            }
            if (confirmedUser != null) {
                recordConsoleLine("INFO", "Login confirmed as '${config.username}'.")
                syncCurrentBpmNow()
            } else {
                recordConsoleLine("INFO", "No login confirmation for '${config.username}' seen within 5s.")
            }
        } else {
            // No login configured at all - still sync the current BPM once
            // right away rather than only from the next change onward.
            syncCurrentBpmNow()
        }
    }

    /**
     * Sends the *current* master BPM immediately, bypassing the "only when
     * it actually changes" rate-limiting gate - used right after a
     * (re)connect/login so the console is correct straight away rather than
     * only updating once a tempo change happens to occur later.
     */
    private suspend fun syncCurrentBpmNow() {
        val master = applicationState.masterClock.value
        if (!master.valid || master.effectiveBpm == null) return
        rateLimiter.reset()
        val now = System.currentTimeMillis()
        if (!rateLimiter.shouldSend(master.effectiveBpm, now)) return
        val masterPlayer = master.masterPlayerNumber?.let { applicationState.players.value[it] }
        val command = CommandTemplateEngine.render(
            config.commandTemplate,
            master,
            trackId = masterPlayer?.rekordboxTrackId,
            title = masterPlayer?.title,
            artist = masterPlayer?.artist,
        )
        sendCommand(command, force = true)
    }

    /**
     * Continuously reads whatever the console sends back (command
     * acknowledgements, prompts, errors, ...) so the console UI shows the
     * real two-way Telnet traffic, not just what we send. A blocking-read
     * timeout (`soTimeout`) is expected and benign whenever the console has
     * nothing to say for a couple of seconds - this loop simply tries again
     * rather than treating that as a connection failure (only the *writer*
     * side's own exceptions, handled in `connectionLoop`, indicate a real
     * problem).
     */
    private suspend fun readLoop() {
        val r = reader ?: return
        while (scope.isActive && status.get().connected) {
            try {
                val line = r.readLine() ?: break // null means the peer closed the connection
                recordConsoleLine("RECEIVED", line)
                _receivedLines.emit(line)
            } catch (e: SocketTimeoutException) {
                // Normal - nothing to read right now, keep waiting.
            } catch (e: Exception) {
                if (status.get().connected) {
                    logger.debug { "MA Telnet read loop ending: ${e.message}" }
                }
                break
            }
        }
    }


    private fun onMasterChanged(master: MasterClockState) {
        if (!config.enabled || !master.valid || master.effectiveBpm == null) return
        scope.launch {
            val now = System.currentTimeMillis()
            if (!rateLimiter.shouldSend(master.effectiveBpm, now)) return@launch
            val masterPlayer = master.masterPlayerNumber?.let { applicationState.players.value[it] }
            val command = CommandTemplateEngine.render(
                config.commandTemplate,
                master,
                trackId = masterPlayer?.rekordboxTrackId,
                title = masterPlayer?.title,
                artist = masterPlayer?.artist,
            )
            sendCommand(command, force = false)
        }
    }

    private suspend fun sendCommand(command: String, force: Boolean) {
        try {
            sendRaw(command)
            status.set(status.get().copy(lastSentAt = System.currentTimeMillis(), lastSentPayload = command))
        } catch (e: Exception) {
            status.set(status.get().copy(lastError = e.message, connected = false))
            logger.warn { "Failed to send GrandMA2 command: ${e.message}" }
        }
    }

    private suspend fun sendRaw(line: String) {
        socketMutex.withLock {
            val w = writer ?: throw IllegalStateException("Not connected")
            try {
                w.write(line)
                w.write("\r\n")
                w.flush()
                if (line.isNotBlank()) recordConsoleLine("SENT", line)
            } catch (e: SocketTimeoutException) {
                throw e
            }
        }
    }

    private fun closeSocket() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        writer = null
        reader = null
        socket = null
    }
}

