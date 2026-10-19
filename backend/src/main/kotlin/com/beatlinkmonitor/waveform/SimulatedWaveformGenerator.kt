package com.beatlinkmonitor.waveform

import kotlin.math.PI
import kotlin.math.sin

/**
 * Produces a deterministic, seeded pseudo-waveform for the simulator so the
 * UI has something stable and track-specific to render without any real Pro
 * DJ Link hardware attached (requirements specification §16.6). This is a rendering aid
 * only - it is never used as a stand-in for real metadata/BPM values
 * (coding guideline #13).
 *
 * Generates proper 3-band RGB color data (low/mid/high frequency energy
 * mapped to blue/green/red, matching the look of Pioneer's real NXS2 color
 * waveforms and `beat-link-trigger`'s own simulator) rather than a flat
 * single-color bar, by sampling a continuous deterministic function of
 * track-position instead of a fixed-size byte array. Because the exact same
 * function is used both for the full-track overview and for zoomed-in
 * "detail" windows ([generateDetailWindow]), both views are visually
 * consistent with each other for a given track/seed.
 */
object SimulatedWaveformGenerator {
    private const val OVERVIEW_COLUMNS = 400

    private data class Params(val lowFreq: Double, val midFreq: Double, val highFreq: Double, val noiseSeed: Long)

    private fun paramsForSeed(seed: Long): Params {
        val random = java.util.Random(seed)
        return Params(
            lowFreq = 6.0 + random.nextDouble() * 5,
            midFreq = 35.0 + random.nextDouble() * 25,
            highFreq = 110.0 + random.nextDouble() * 90,
            noiseSeed = random.nextLong(),
        )
    }

    /** Cheap, deterministic pseudo-random value in [0,1] for a given (seed, trackFraction) pair. */
    private fun hashNoise(seed: Long, fraction: Double): Double {
        val x = (fraction * 200_000).toLong()
        var h = seed xor (x * -0x61c8864680b583ebL)
        h = h xor (h ushr 33)
        h *= -0xae502812aa7333L
        h = h xor (h ushr 29)
        return (h and 0xFFFFFF).toDouble() / 0xFFFFFF.toDouble()
    }

    private fun sampleAt(params: Params, fraction: Double): Pair<Int, String> {
        val t = fraction.coerceIn(0.0, 1.0)
        val low = 0.5 + 0.5 * sin(t * 2 * PI * params.lowFreq)
        val mid = 0.5 + 0.5 * sin(t * 2 * PI * params.midFreq + 1.3)
        val high = 0.5 + 0.5 * sin(t * 2 * PI * params.highFreq + 2.1)
        val noise = hashNoise(params.noiseSeed, t)

        val energy = ((low * 0.5 + mid * 0.3 + high * 0.2) * 0.8 + noise * 0.2).coerceIn(0.05, 1.0)
        val height = (energy * 31).toInt().coerceIn(0, 31)

        // Pioneer color waveform convention: bass = blue, mids = green/yellow, highs = red/white.
        val r = ((high * 0.7 + noise * 0.3) * 255).toInt().coerceIn(0, 255)
        val g = ((mid * 0.75 + noise * 0.25) * 255).toInt().coerceIn(0, 255)
        val b = ((low * 0.85 + noise * 0.15) * 255).toInt().coerceIn(0, 255)
        return height to "#%02x%02x%02x".format(r, g, b)
    }

    /** Full-track overview heights (0..31), one per column, for the top-row/overview waveform. */
    fun generate(seed: Long): List<Int> {
        val params = paramsForSeed(seed)
        return (0 until OVERVIEW_COLUMNS).map { i -> sampleAt(params, i.toDouble() / (OVERVIEW_COLUMNS - 1)).first }
    }

    /** Full-track overview colors, parallel to [generate]'s heights. */
    fun generateColors(seed: Long): List<String> {
        val params = paramsForSeed(seed)
        return (0 until OVERVIEW_COLUMNS).map { i -> sampleAt(params, i.toDouble() / (OVERVIEW_COLUMNS - 1)).second }
    }

    /**
     * Full-track detail waveform at a fixed [columns] resolution, sampled
     * directly from fraction 0.0 to 1.0 of the track (no playhead/window
     * involved). Used so the Live View's detail row can fetch the whole
     * track's zoomed-in waveform exactly **once** per track load - matching
     * how `beat-link-trigger`'s own `WaveformDetailComponent` only re-fetches
     * waveform data on a track change and otherwise just re-paints the
     * already-held data at the animated position (requirements specification §9.4,
     * coding guideline #13). Re-fetching this periodically while playing
     * was the root cause of visible flicker from out-of-order network
     * responses racing each other.
     */
    fun generateDetailFull(seed: Long, columns: Int): Pair<List<Int>, List<String>> {
        if (columns <= 0) return emptyList<Int>() to emptyList()
        val params = paramsForSeed(seed)
        val heights = ArrayList<Int>(columns)
        val colors = ArrayList<String>(columns)
        for (col in 0 until columns) {
            val (h, c) = sampleAt(params, col.toDouble() / (columns - 1).coerceAtLeast(1))
            heights.add(h)
            colors.add(c)
        }
        return heights to colors
    }

    /**
     * Synthesizes a constant-tempo beat grid for a simulated/procedural
     * track - beat 1 (a downbeat) starts at track time 0, with subsequent
     * beats spaced evenly at `60000 / bpm` ms and cycling through bar
     * positions 1..4, matching the standard 4/4 structure used throughout
     * the rest of the simulator (requirements specification §16.6). Real hardware and
     * sample tracks use their actual analyzed beat grid instead - this is
     * only a reasonable approximation for purely synthetic demo tracks that
     * have no real analysis data to draw from.
     */
    fun generateBeatGrid(bpm: Double, durationMs: Long): Pair<List<Long>, List<Int>> {
        if (bpm <= 0 || durationMs <= 0) return emptyList<Long>() to emptyList()
        val beatIntervalMs = 60_000.0 / bpm
        val times = ArrayList<Long>()
        val beatsWithinBar = ArrayList<Int>()
        var beatIndex = 0
        var timeMs = 0.0
        while (timeMs < durationMs) {
            times.add(timeMs.toLong())
            beatsWithinBar.add((beatIndex % 4) + 1)
            beatIndex++
            timeMs += beatIntervalMs
        }
        return times to beatsWithinBar
    }

    /**
     * Samples a zoomed-in window of [columns] pixels around [positionMs],
     * showing [beforeMs] of track before the playhead and [afterMs] after it
     * - used for the scrolling "detail" waveform row in the Live View
     * (requirements specification §9.4). Positions outside the track (before 0 or past
     * [durationMs]) render as silence (height 0, black) rather than wrapping
     * or repeating, matching how a real CDJ detail display behaves at the
     * very start/end of a track.
     */
    fun generateDetailWindow(
        seed: Long,
        positionMs: Long,
        durationMs: Long,
        beforeMs: Long,
        afterMs: Long,
        columns: Int,
    ): Pair<List<Int>, List<String>> {
        if (durationMs <= 0 || columns <= 0) {
            return List(columns.coerceAtLeast(0)) { 0 } to List(columns.coerceAtLeast(0)) { "#000000" }
        }
        val params = paramsForSeed(seed)
        val startMs = positionMs - beforeMs
        val totalWindowMs = (beforeMs + afterMs).coerceAtLeast(1)
        val heights = ArrayList<Int>(columns)
        val colors = ArrayList<String>(columns)
        for (col in 0 until columns) {
            val ms = startMs + totalWindowMs.toDouble() * col / (columns - 1).coerceAtLeast(1)
            if (ms < 0 || ms > durationMs) {
                heights.add(0)
                colors.add("#000000")
            } else {
                val (h, c) = sampleAt(params, ms / durationMs.toDouble())
                heights.add(h)
                colors.add(c)
            }
        }
        return heights to colors
    }
}
