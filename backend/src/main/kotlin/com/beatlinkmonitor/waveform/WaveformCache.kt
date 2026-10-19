package com.beatlinkmonitor.waveform

import com.beatlinkmonitor.model.WaveformType
import org.deepsymmetry.beatlink.data.BeatGrid
import org.deepsymmetry.beatlink.data.WaveformDetail
import org.deepsymmetry.beatlink.data.WaveformFinder
import org.deepsymmetry.beatlink.data.WaveformPreview
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache key per requirements specification §31: track id + source player + source slot,
 * so that a track change (even the same track re-loaded from a different
 * slot) is always detected correctly.
 */
data class WaveformCacheKey(val trackId: Int, val player: Int, val slot: String)

/**
 * Caches the actual `beat-link` waveform/beat-grid objects (not raw bytes)
 * so pixel decoding can reuse the library's own, already-correct
 * `segmentHeight`/`segmentColor`/`getTimeWithinTrack` accessors instead of
 * re-implementing the Pioneer data layout ourselves (requirements specification §9, §30,
 * §31).
 */
data class CachedWaveform(
    val type: WaveformType,
    val preview: WaveformPreview?,
    val detail: WaveformDetail?,
    val beatGrid: BeatGrid? = null,
)

/**
 * Caches waveform preview/detail data so it doesn't have to be re-requested
 * from the player on every UI refresh (requirements specification §9, §30, §31).
 */
class WaveformCache {
    private val entries = ConcurrentHashMap<WaveformCacheKey, CachedWaveform>()

    fun put(key: WaveformCacheKey, waveform: CachedWaveform) {
        entries[key] = waveform
    }

    fun get(key: WaveformCacheKey): CachedWaveform? = entries[key]

    fun evictForPlayer(player: Int) {
        entries.keys.removeIf { it.player == player }
    }

    fun clear() = entries.clear()
}

/**
 * Determines the waveform representation to advertise to the UI, following
 * the mandatory fallback chain (requirements specification §9.3):
 *
 * 3-Band -> RGB -> Classic -> No Waveform
 */
object WaveformFallback {
    fun resolve(hasThreeBand: Boolean, hasRgb: Boolean, hasClassic: Boolean): WaveformType = when {
        hasThreeBand -> WaveformType.THREE_BAND
        hasRgb -> WaveformType.RGB
        hasClassic -> WaveformType.CLASSIC
        else -> WaveformType.NONE
    }
}

/**
 * Per-frequency-band heights (0-31, directly from beat-link's own
 * `segmentHeight(segment, scale, ThreeBandLayer)` accessors - Coding-
 * Richtlinie #13: never invent pixel data) for a CDJ-3000's native
 * "three-band" waveform style. Rendered by the frontend as three
 * overlapping colored layers (dark blue = low, amber = mid, white = high -
 * beat-link's own `WaveformFinder.ThreeBandLayer` colors) in back-to-front
 * z-order high/mid/low, which correctly reproduces the real banded look for
 * both of the two different value shapes beat-link returns: preview heights
 * are *cumulative* (high already includes mid+low, so drawing it first/
 * tallest and then progressively shorter layers on top naturally reveals
 * each band), while detail heights are independent per-band values (where
 * the same fixed z-order is instead a deliberate, reasonable compositing
 * choice - not exact alpha-blended math - to approximate the same official
 * color scheme without needing to invent any additional data).
 */
data class ThreeBandHeights(
    val low: List<Int>,
    val mid: List<Int>,
    val high: List<Int>,
)

/**
 * Converts a real `beat-link` [WaveformPreview] into plain height/color
 * arrays for the JSON API, reusing the library's own per-segment decoding
 * (coding guideline #13: never invent pixel data ourselves).
 */
object WaveformPixelDecoder {
    fun decodePreview(preview: WaveformPreview): Pair<List<Int>, List<String>?> {
        val heights = (0 until preview.segmentCount).map { preview.segmentHeight(it, false) }
        val colors = if (preview.isColor) {
            (0 until preview.segmentCount).map { toHex(preview.segmentColor(it, false)) }
        } else {
            null
        }
        return heights to colors
    }

    /** Three-band counterpart of [decodePreview] - see [ThreeBandHeights]'s doc comment. */
    fun decodePreviewThreeBand(preview: WaveformPreview): ThreeBandHeights {
        val indices = 0 until preview.segmentCount
        return ThreeBandHeights(
            low = indices.map { preview.segmentHeight(it, WaveformFinder.ThreeBandLayer.LOW) },
            mid = indices.map { preview.segmentHeight(it, WaveformFinder.ThreeBandLayer.MID) },
            high = indices.map { preview.segmentHeight(it, WaveformFinder.ThreeBandLayer.HIGH) },
        )
    }

    fun decodeDetail(detail: WaveformDetail, maxColumns: Int = 800): Pair<List<Int>, List<String>?> {
        val frameCount = detail.frameCount
        if (frameCount <= 0) return emptyList<Int>() to null
        val step = maxOf(1, frameCount / maxColumns)
        val indices = (0 until frameCount step step)
        val heights = indices.map { detail.segmentHeight(it, step) }
        val colors = if (detail.isColor) indices.map { toHex(detail.segmentColor(it, step)) } else null
        return heights to colors
    }

    /** Three-band counterpart of [decodeDetail] - see [ThreeBandHeights]'s doc comment. */
    fun decodeDetailThreeBand(detail: WaveformDetail, maxColumns: Int = 800): ThreeBandHeights {
        val frameCount = detail.frameCount
        if (frameCount <= 0) return ThreeBandHeights(emptyList(), emptyList(), emptyList())
        val step = maxOf(1, frameCount / maxColumns)
        val indices = (0 until frameCount step step)
        return ThreeBandHeights(
            low = indices.map { detail.segmentHeight(it, step, WaveformFinder.ThreeBandLayer.LOW) },
            mid = indices.map { detail.segmentHeight(it, step, WaveformFinder.ThreeBandLayer.MID) },
            high = indices.map { detail.segmentHeight(it, step, WaveformFinder.ThreeBandLayer.HIGH) },
        )
    }

    /**
     * Slices a zoomed-in window of [columns] pixels out of a full-track
     * [WaveformDetail], centered around [positionMs] with [beforeMs] shown
     * before the playhead and [afterMs] after it - the real-hardware
     * counterpart to [com.beatlinkmonitor.waveform.SimulatedWaveformGenerator.generateDetailWindow],
     * used for the Live View's scrolling detail row (requirements specification §9.4).
     * Positions outside the track render as silence rather than clamping to
     * the first/last frame, so the waveform doesn't visually "stick".
     */
    fun decodeDetailWindow(detail: WaveformDetail, positionMs: Long, beforeMs: Long, afterMs: Long, columns: Int): Pair<List<Int>, List<String>?> {
        val frameCount = detail.frameCount
        if (frameCount <= 0 || columns <= 0) {
            return List(columns.coerceAtLeast(0)) { 0 } to null
        }
        val startFrame = org.deepsymmetry.beatlink.Util.timeToHalfFrame(positionMs - beforeMs)
        val endFrame = org.deepsymmetry.beatlink.Util.timeToHalfFrame(positionMs + afterMs)
        val totalFrames = (endFrame - startFrame).coerceAtLeast(1)
        val heights = ArrayList<Int>(columns)
        val colors = if (detail.isColor) ArrayList<String>(columns) else null
        for (col in 0 until columns) {
            val frameIdx = (startFrame + totalFrames.toDouble() * col / (columns - 1).coerceAtLeast(1)).toInt()
            if (frameIdx < 0 || frameIdx >= frameCount) {
                heights.add(0)
                colors?.add("#000000")
            } else {
                heights.add(detail.segmentHeight(frameIdx, 1))
                colors?.add(toHex(detail.segmentColor(frameIdx, 1)))
            }
        }
        return heights to colors
    }

    /** Three-band counterpart of [decodeDetailWindow] - see [ThreeBandHeights]'s doc comment. */
    fun decodeDetailWindowThreeBand(detail: WaveformDetail, positionMs: Long, beforeMs: Long, afterMs: Long, columns: Int): ThreeBandHeights {
        val frameCount = detail.frameCount
        if (frameCount <= 0 || columns <= 0) {
            val empty = List(columns.coerceAtLeast(0)) { 0 }
            return ThreeBandHeights(empty, empty, empty)
        }
        val startFrame = org.deepsymmetry.beatlink.Util.timeToHalfFrame(positionMs - beforeMs)
        val endFrame = org.deepsymmetry.beatlink.Util.timeToHalfFrame(positionMs + afterMs)
        val totalFrames = (endFrame - startFrame).coerceAtLeast(1)
        val low = ArrayList<Int>(columns)
        val mid = ArrayList<Int>(columns)
        val high = ArrayList<Int>(columns)
        for (col in 0 until columns) {
            val frameIdx = (startFrame + totalFrames.toDouble() * col / (columns - 1).coerceAtLeast(1)).toInt()
            if (frameIdx < 0 || frameIdx >= frameCount) {
                low.add(0)
                mid.add(0)
                high.add(0)
            } else {
                low.add(detail.segmentHeight(frameIdx, 1, WaveformFinder.ThreeBandLayer.LOW))
                mid.add(detail.segmentHeight(frameIdx, 1, WaveformFinder.ThreeBandLayer.MID))
                high.add(detail.segmentHeight(frameIdx, 1, WaveformFinder.ThreeBandLayer.HIGH))
            }
        }
        return ThreeBandHeights(low, mid, high)
    }

    /**
     * Decodes the **entire** track's detail waveform at a fixed [columns]
     * resolution, evenly spanning every half-frame from start to end - the
     * real-hardware/sample-track counterpart to
     * [SimulatedWaveformGenerator.generateDetailFull]. Meant to be fetched
     * once per track load (not on a timer) so the Live View's scrolling
     * detail row never has to race multiple in-flight network responses
     * against each other while a player is actively running.
     */
    fun decodeDetailFull(detail: WaveformDetail, columns: Int): Pair<List<Int>, List<String>?> {
        val frameCount = detail.frameCount
        if (frameCount <= 0 || columns <= 0) {
            return List(columns.coerceAtLeast(0)) { 0 } to null
        }
        val heights = ArrayList<Int>(columns)
        val colors = if (detail.isColor) ArrayList<String>(columns) else null
        for (col in 0 until columns) {
            val frameIdx = (frameCount.toDouble() * col / (columns - 1).coerceAtLeast(1)).toInt().coerceIn(0, frameCount - 1)
            heights.add(detail.segmentHeight(frameIdx, 1))
            colors?.add(toHex(detail.segmentColor(frameIdx, 1)))
        }
        return heights to colors
    }

    /** Three-band counterpart of [decodeDetailFull] - see [ThreeBandHeights]'s doc comment. */
    fun decodeDetailFullThreeBand(detail: WaveformDetail, columns: Int): ThreeBandHeights {
        val frameCount = detail.frameCount
        if (frameCount <= 0 || columns <= 0) {
            val empty = List(columns.coerceAtLeast(0)) { 0 }
            return ThreeBandHeights(empty, empty, empty)
        }
        val low = ArrayList<Int>(columns)
        val mid = ArrayList<Int>(columns)
        val high = ArrayList<Int>(columns)
        for (col in 0 until columns) {
            val frameIdx = (frameCount.toDouble() * col / (columns - 1).coerceAtLeast(1)).toInt().coerceIn(0, frameCount - 1)
            low.add(detail.segmentHeight(frameIdx, 1, WaveformFinder.ThreeBandLayer.LOW))
            mid.add(detail.segmentHeight(frameIdx, 1, WaveformFinder.ThreeBandLayer.MID))
            high.add(detail.segmentHeight(frameIdx, 1, WaveformFinder.ThreeBandLayer.HIGH))
        }
        return ThreeBandHeights(low, mid, high)
    }

    private fun toHex(color: java.awt.Color): String =
        "#%02x%02x%02x".format(color.red, color.green, color.blue)
}

/**
 * Converts a real `beat-link` [BeatGrid] into plain parallel
 * time/beat-within-bar lists for the JSON API (requirements specification: Live View
 * shows the beat grid above the detail waveform, like a CDJ).
 */
object BeatGridDecoder {
    fun decode(grid: org.deepsymmetry.beatlink.data.BeatGrid): Pair<List<Long>, List<Int>> {
        val times = ArrayList<Long>(grid.beatCount)
        val beats = ArrayList<Int>(grid.beatCount)
        for (i in 1..grid.beatCount) {
            times.add(grid.getTimeWithinTrack(i))
            beats.add(grid.getBeatWithinBar(i))
        }
        return times to beats
    }
}

