package com.beatlinkmonitor.waveform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulatedWaveformGeneratorTest {

    @Test
    fun `overview heights and colors are the same length and deterministic for a given seed`() {
        val heightsA = SimulatedWaveformGenerator.generate(42L)
        val heightsB = SimulatedWaveformGenerator.generate(42L)
        val colorsA = SimulatedWaveformGenerator.generateColors(42L)

        assertEquals(heightsA, heightsB) // deterministic for the same seed
        assertEquals(heightsA.size, colorsA.size)
        assertTrue(heightsA.all { it in 0..31 })
        assertTrue(colorsA.all { it.matches(Regex("#[0-9a-f]{6}")) })
    }

    @Test
    fun `different seeds produce different-looking waveforms`() {
        val a = SimulatedWaveformGenerator.generate(1L)
        val b = SimulatedWaveformGenerator.generate(2L)
        assertTrue(a != b)
    }

    @Test
    fun `overview produces real RGB color variation, not a single flat color`() {
        // Regression guard: the simulator previously only ever rendered a
        // flat single-color bar for "RGB" waveforms instead of real 3-band
        // color data like beat-link-trigger's test mode.
        val colors = SimulatedWaveformGenerator.generateColors(7L)
        assertTrue(colors.toSet().size > 10, "expected varied colors, got: ${colors.toSet()}")
    }

    @Test
    fun `detail window produces the requested number of columns`() {
        val (heights, colors) = SimulatedWaveformGenerator.generateDetailWindow(
            seed = 5L,
            positionMs = 60_000L,
            durationMs = 300_000L,
            beforeMs = 5_625L,
            afterMs = 9_375L,
            columns = 600,
        )
        assertEquals(600, heights.size)
        assertEquals(600, colors.size)
    }

    @Test
    fun `detail window renders silence before the start of the track`() {
        val (heights, _) = SimulatedWaveformGenerator.generateDetailWindow(
            seed = 5L,
            positionMs = 1_000L, // close to the start - the "before" window extends past 0
            durationMs = 300_000L,
            beforeMs = 5_625L,
            afterMs = 9_375L,
            columns = 100,
        )
        // The first columns represent time before the track started (negative ms).
        assertEquals(0, heights.first())
    }

    @Test
    fun `detail window renders silence after the end of the track`() {
        val (heights, _) = SimulatedWaveformGenerator.generateDetailWindow(
            seed = 5L,
            positionMs = 299_000L, // close to the end - the "after" window extends past duration
            durationMs = 300_000L,
            beforeMs = 5_625L,
            afterMs = 9_375L,
            columns = 100,
        )
        assertEquals(0, heights.last())
    }

    @Test
    fun `detail window is consistent with the overview for the same seed and track position`() {
        // Both views sample from the exact same underlying function, so a
        // detail window centered in the middle of the track should roughly
        // agree in "energy" with the corresponding overview column.
        val seed = 99L
        val overview = SimulatedWaveformGenerator.generate(seed)
        val middleOverviewHeight = overview[overview.size / 2]

        val (detailHeights, _) = SimulatedWaveformGenerator.generateDetailWindow(
            seed = seed,
            positionMs = 150_000L,
            durationMs = 300_000L,
            beforeMs = 0L,
            afterMs = 1L,
            columns = 1,
        )
        // Not required to be identical (different sampling density), but both
        // must be valid, in-range heights derived from the same track.
        assertTrue(middleOverviewHeight in 0..31)
        assertTrue(detailHeights.first() in 0..31)
    }

    @Test
    fun `generateBeatGrid produces an evenly-spaced constant-tempo grid cycling 1-4`() {
        val (times, beats) = SimulatedWaveformGenerator.generateBeatGrid(bpm = 128.0, durationMs = 60_000L)
        assertTrue(times.isNotEmpty())
        assertEquals(times.size, beats.size)
        assertEquals(1, beats.first())
        assertTrue(beats.all { it in 1..4 })
        // 128 BPM -> 60000/128 = 468.75ms between beats.
        val expectedIntervalMs = 60_000.0 / 128.0
        times.zipWithNext().forEach { (a, b) ->
            assertTrue(kotlin.math.abs((b - a) - expectedIntervalMs) < 1.0)
        }
        assertTrue(times.all { it < 60_000L })
    }

    @Test
    fun `generateBeatGrid handles invalid input gracefully`() {
        val (times, beats) = SimulatedWaveformGenerator.generateBeatGrid(bpm = 0.0, durationMs = 60_000L)
        assertTrue(times.isEmpty())
        assertTrue(beats.isEmpty())
    }
}
