package com.beatlinkmonitor.api

import com.beatlinkmonitor.diagnostics.DiagnosticsSnapshot
import com.beatlinkmonitor.model.MasterClockState
import com.beatlinkmonitor.model.PlayerState
import com.beatlinkmonitor.outputs.OutputStatus
import kotlinx.serialization.Serializable

@Serializable
sealed interface ServerMessage

@Serializable
data class StateSnapshotMessage(
    val type: String = "state",
    val players: List<PlayerState>,
    val masterClock: MasterClockState,
    val outputs: List<OutputStatus>,
) : ServerMessage

@Serializable
data class DiagnosticsMessage(
    val type: String = "diagnostics",
    val diagnostics: DiagnosticsSnapshot,
) : ServerMessage

@Serializable
data class ErrorMessage(
    val type: String = "error",
    val message: String,
) : ServerMessage

@Serializable
data class ClientCommand(
    val action: String,
    val deviceNumber: Int? = null,
    val value: Double? = null,
    val boolValue: Boolean? = null,
    val trackId: Long? = null,
    val positionMs: Long? = null,
)

/** Body for `POST /api/outputs/grandma/send` - an arbitrary user-typed Telnet console command. */
@Serializable
data class TelnetSendCommand(
    val command: String,
)

/**
 * Response for `POST /api/config/application`: tells the UI whether a full
 * application restart (quit + relaunch, not just an in-process reconnect) is
 * required for the saved change to actually take effect - currently true
 * only when the Pro DJ Link network interface or our own virtual player
 * number changed. A full restart is required rather than an automatic
 * in-process reconnect because the latter was confirmed, on real hardware,
 * to be unreliable: it can leave `beat-link`'s VirtualCdj in a state that
 * actively conflicts with a real player's own device-number claim.
 */
@Serializable
data class ApplicationConfigSaveResult(
    val restartRequired: Boolean,
)
