package com.beatlinkmonitor.diagnostics

import com.beatlinkmonitor.outputs.OutputManager
import com.beatlinkmonitor.prodjlink.NetworkInterfaceService
import com.beatlinkmonitor.prodjlink.ProDjLinkAdapter
import com.beatlinkmonitor.simulator.SimulationService
import com.beatlinkmonitor.state.ApplicationState
import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticsSnapshot(
    val applicationVersion: String,
    val javaVersion: String,
    val operatingSystem: String,
    val networkInterfaces: List<String>,
    val localIp: String?,
    val proDjLinkRunning: Boolean,
    val discoveredDevices: Int,
    val currentMasterPlayer: Int?,
    val masterBpm: Double?,
    val abletonLinkConnected: Boolean,
    val telnetConnected: Boolean,
    val lastTelnetCommand: String?,
    val uiWebSocketConnections: Int,
    val simulatorRunning: Boolean,
    val logDirectory: String,
    val ownDeviceNumber: Int?,
)

/**
 * Backing data for the mandatory diagnostics view (requirements specification §25).
 */
class DiagnosticsService(
    private val applicationState: ApplicationState,
    private val proDjLinkAdapter: ProDjLinkAdapter,
    private val simulationService: SimulationService,
    private val outputManager: OutputManager,
    private val version: String,
) {
    @Volatile
    var uiWebSocketConnections: Int = 0

    @Volatile
    var simulatorRunning: Boolean = false

    fun snapshot(): DiagnosticsSnapshot {
        val statuses = outputManager.statuses()
        val grandMa = statuses.firstOrNull { it.name == "GrandMA2 Telnet" }
        val link = statuses.firstOrNull { it.name == "Ableton Link" }
        val master = applicationState.masterClock.value
        return DiagnosticsSnapshot(
            applicationVersion = version,
            javaVersion = System.getProperty("java.version") ?: "unknown",
            operatingSystem = "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
            networkInterfaces = NetworkInterfaceService.listInterfaces().map { it.name },
            localIp = NetworkInterfaceService.listInterfaces().flatMap { it.addresses }.firstOrNull(),
            proDjLinkRunning = proDjLinkAdapter.isRunning(),
            discoveredDevices = applicationState.players.value.size,
            currentMasterPlayer = master.masterPlayerNumber,
            masterBpm = master.effectiveBpm,
            abletonLinkConnected = link?.connected ?: false,
            telnetConnected = grandMa?.connected ?: false,
            lastTelnetCommand = grandMa?.lastSentPayload,
            uiWebSocketConnections = uiWebSocketConnections,
            simulatorRunning = simulatorRunning,
            logDirectory = System.getProperty("BEATLINK_LOG_DIR") ?: System.getenv("BEATLINK_LOG_DIR") ?: ".",
            ownDeviceNumber = proDjLinkAdapter.ownDeviceNumber(),
        )
    }
}
