package com.beatlinkmonitor.state

import com.beatlinkmonitor.model.PlayerSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplicationStateTest {

    @Test
    fun `master becomes valid once a player reports tempoMaster`() = runTest {
        val state = ApplicationState()
        assertFalse(state.masterClock.value.valid)

        state.upsertPlayer(1) {
            it.copy(tempoMaster = true, originalBpm = 140.0, effectiveBpm = 140.0, source = PlayerSource.SIMULATED)
        }

        assertTrue(state.masterClock.value.valid)
        assertEquals(1, state.masterClock.value.masterPlayerNumber)
        assertEquals(140.0, state.masterClock.value.effectiveBpm)
    }

    @Test
    fun `Scenario C - master handover moves from player 1 to player 2`() = runTest {
        val state = ApplicationState()
        state.upsertPlayer(1) { it.copy(tempoMaster = true, originalBpm = 140.0, effectiveBpm = 140.0) }
        state.upsertPlayer(2) { it.copy(tempoMaster = false, originalBpm = 128.0, effectiveBpm = 128.0) }

        assertEquals(1, state.masterClock.value.masterPlayerNumber)

        // Handover: player 2 becomes master, player 1 yields.
        state.upsertPlayer(2) { it.copy(tempoMaster = true) }
        state.upsertPlayer(1) { it.copy(tempoMaster = false) }

        assertEquals(2, state.masterClock.value.masterPlayerNumber)
        assertEquals(128.0, state.masterClock.value.effectiveBpm)
    }

    @Test
    fun `Scenario D - master disconnect invalidates the master clock`() = runTest {
        val state = ApplicationState()
        state.upsertPlayer(1) { it.copy(tempoMaster = true, originalBpm = 140.0, effectiveBpm = 140.0) }
        assertTrue(state.masterClock.value.valid)

        state.removePlayer(1)

        assertFalse(state.masterClock.value.valid)
        assertEquals(null, state.masterClock.value.masterPlayerNumber)
    }

    @Test
    fun `removing a non-master player does not affect the master clock`() = runTest {
        val state = ApplicationState()
        state.upsertPlayer(1) { it.copy(tempoMaster = true, originalBpm = 140.0, effectiveBpm = 140.0) }
        state.upsertPlayer(2) { it.copy(tempoMaster = false, originalBpm = 128.0, effectiveBpm = 128.0) }

        state.removePlayer(2)

        assertTrue(state.masterClock.value.valid)
        assertEquals(1, state.masterClock.value.masterPlayerNumber)
    }
}
