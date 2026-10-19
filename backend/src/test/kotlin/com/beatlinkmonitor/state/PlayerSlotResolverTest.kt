package com.beatlinkmonitor.state

import com.beatlinkmonitor.config.PlayerSlotConfig
import com.beatlinkmonitor.model.MasterClockState
import com.beatlinkmonitor.model.PlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerSlotResolverTest {

    private val players = mapOf(
        1 to PlayerState(deviceNumber = 1, connected = true, onAir = true),
        2 to PlayerState(deviceNumber = 2, connected = true, onAir = false),
        3 to PlayerState(deviceNumber = 3, connected = true, onAir = true),
    )

    @Test
    fun `manual PLAYER mapping returns the configured player number`() {
        val slot = PlayerSlotConfig(slot = 1, sourceType = "PLAYER", playerNumber = 3)
        assertEquals(3, PlayerSlotResolver.resolve(slot, players, MasterClockState.INVALID))
    }

    @Test
    fun `CURRENT_MASTER follows the active tempo master`() {
        val slot = PlayerSlotConfig(slot = 1, sourceType = "CURRENT_MASTER")
        val master = MasterClockState(masterPlayerNumber = 2, valid = true)
        assertEquals(2, PlayerSlotResolver.resolve(slot, players, master))
    }

    @Test
    fun `CURRENT_MASTER is null when there is no valid master`() {
        val slot = PlayerSlotConfig(slot = 1, sourceType = "CURRENT_MASTER")
        assertNull(PlayerSlotResolver.resolve(slot, players, MasterClockState.INVALID))
    }

    @Test
    fun `AUTO prefers the current master when present`() {
        val slot = PlayerSlotConfig(slot = 1, sourceType = "AUTO")
        val master = MasterClockState(masterPlayerNumber = 3, valid = true)
        assertEquals(3, PlayerSlotResolver.resolve(slot, players, master))
    }

    @Test
    fun `AUTO falls back to the lowest connected player when there is no master`() {
        val slot = PlayerSlotConfig(slot = 1, sourceType = "AUTO")
        assertEquals(1, PlayerSlotResolver.resolve(slot, players, MasterClockState.INVALID))
    }

    @Test
    fun `ON_AIR_1 and ON_AIR_2 pick the first two on-air players in order`() {
        val slot1 = PlayerSlotConfig(slot = 1, sourceType = "ON_AIR_1")
        val slot2 = PlayerSlotConfig(slot = 2, sourceType = "ON_AIR_2")
        assertEquals(1, PlayerSlotResolver.resolve(slot1, players, MasterClockState.INVALID))
        assertEquals(3, PlayerSlotResolver.resolve(slot2, players, MasterClockState.INVALID))
    }
}
