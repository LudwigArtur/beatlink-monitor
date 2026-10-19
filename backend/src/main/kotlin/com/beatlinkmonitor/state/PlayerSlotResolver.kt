package com.beatlinkmonitor.state

import com.beatlinkmonitor.config.PlayerSlotConfig
import com.beatlinkmonitor.model.MasterClockState
import com.beatlinkmonitor.model.PlayerState

/**
 * Resolves a UI display slot to the concrete Pro DJ Link player number it
 * should currently show (requirements specification §5). Version 1 requires at least the
 * `AUTO` and `CURRENT_MASTER` dynamic sources; the remaining sources listed in
 * the requirements specification (`ON_AIR_1/2`, `LAST_MASTER`, `NEXT_ACTIVE_PLAYER`) may be
 * added later without touching manual `PLAYER` mapping.
 */
object PlayerSlotResolver {

    fun resolve(
        slot: PlayerSlotConfig,
        players: Map<Int, PlayerState>,
        master: MasterClockState,
    ): Int? = when (slot.sourceType) {
        "PLAYER" -> slot.playerNumber
        "CURRENT_MASTER" -> master.masterPlayerNumber
        "AUTO" -> master.masterPlayerNumber ?: players.values
            .filter { it.connected }
            .minByOrNull { it.deviceNumber }
            ?.deviceNumber
        "ON_AIR_1" -> players.values.filter { it.onAir }.sortedBy { it.deviceNumber }.getOrNull(0)?.deviceNumber
        "ON_AIR_2" -> players.values.filter { it.onAir }.sortedBy { it.deviceNumber }.getOrNull(1)?.deviceNumber
        else -> null // LAST_MASTER / NEXT_ACTIVE_PLAYER: reserved for a future version (requirements specification §5.2)
    }
}
