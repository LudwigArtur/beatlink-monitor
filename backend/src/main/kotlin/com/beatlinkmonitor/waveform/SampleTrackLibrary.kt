package com.beatlinkmonitor.waveform

import org.deepsymmetry.beatlink.CdjStatus
import org.deepsymmetry.beatlink.data.BeatGrid
import org.deepsymmetry.beatlink.data.DataReference
import org.deepsymmetry.beatlink.data.WaveformDetail
import org.deepsymmetry.beatlink.data.WaveformPreview
import java.nio.ByteBuffer

/**
 * Real, pre-analyzed waveform data for two sample tracks, used by the
 * simulator so the Live View and Monitor waveforms can be exercised with
 * genuine Pro DJ Link color-waveform data instead of only a procedural
 * approximation (requirements specification §16.6). The raw segment bytes are the same
 * waveform *analysis* data bundled with Deep Symmetry's `beat-link-trigger`
 * sample tracks for this exact purpose - see
 * `src/main/resources/sampletracks/README.md` for attribution/licensing
 * details. No audio or artwork is included or required.
 *
 * Because these are real `beat-link` [WaveformPreview]/[WaveformDetail]
 * objects (constructed via the library's own raw-byte-buffer constructors),
 * the exact same [WaveformPixelDecoder] used for real hardware decodes them
 * - there is no separate "fake" code path for sample-track waveforms.
 */
object SampleTrackLibrary {

    /** Metadata + identifying trackId for a bundled sample track. */
    data class SampleTrack(
        val trackId: Long,
        val title: String,
        val artist: String,
        val originalBpm: Double,
        val durationMs: Long,
    )

    val TRACKS: List<SampleTrack> = listOf(
        SampleTrack(trackId = 9001L, title = "Concrete Angel (Original Mix)", artist = "Gareth Emery", originalBpm = 130.0, durationMs = 429_000L),
        SampleTrack(trackId = 9002L, title = "Transcendence", artist = "Lindsey Stirling", originalBpm = 127.0, durationMs = 226_000L),
    )

    private val resourcePaths: Map<Long, String> = mapOf(
        9001L to "track1",
        9002L to "track2",
    )

    private data class LoadedWaveform(val preview: WaveformPreview, val detail: WaveformDetail, val beatGrid: BeatGrid?)

    private val cache: Map<Long, LoadedWaveform> by lazy {
        resourcePaths.mapNotNull { (trackId, folder) ->
            loadWaveform(trackId, folder)?.let { trackId to it }
        }.toMap()
    }

    private fun loadWaveform(trackId: Long, folder: String): LoadedWaveform? {
        val previewBytes = readResource("/sampletracks/$folder/preview-color.data") ?: return null
        val detailBytes = readResource("/sampletracks/$folder/detail-color.data") ?: return null
        val reference = DataReference(0, CdjStatus.TrackSourceSlot.USB_SLOT, trackId.toInt())
        val preview = WaveformPreview(reference, ByteBuffer.wrap(previewBytes), true)
        val detail = WaveformDetail(reference, ByteBuffer.wrap(detailBytes), true)
        val bpm = TRACKS.first { it.trackId == trackId }.originalBpm
        val beatGrid = loadBeatGrid(reference, folder, bpm)
        return LoadedWaveform(preview, detail, beatGrid)
    }

    /**
     * Loads the real beat grid (beat timestamps + position within bar) for a
     * sample track, re-exported from `beat-link-trigger`'s own sample track
     * analysis data (see `beatgrid.csv` / README) via the same raw-array
     * [BeatGrid] constructor `beat-link` itself uses - so it decodes
     * identically to a real hardware-supplied beat grid.
     */
    private fun loadBeatGrid(reference: DataReference, folder: String, bpm: Double): BeatGrid? {
        val lines = SampleTrackLibrary::class.java.getResourceAsStream("/sampletracks/$folder/beatgrid.csv")
            ?.bufferedReader()?.use { it.readLines() }
            ?: return null
        val beatWithinBarValues = IntArray(lines.size)
        val timeWithinTrackValues = LongArray(lines.size)
        val bpmValues = IntArray(lines.size) { Math.round(bpm * 100).toInt() }
        lines.forEachIndexed { index, line ->
            val (timeMs, beatWithinBar) = line.split(",")
            timeWithinTrackValues[index] = timeMs.toLong()
            beatWithinBarValues[index] = beatWithinBar.toInt()
        }
        return BeatGrid(reference, beatWithinBarValues, bpmValues, timeWithinTrackValues)
    }

    private fun readResource(path: String): ByteArray? =
        SampleTrackLibrary::class.java.getResourceAsStream(path)?.use { it.readBytes() }

    fun preview(trackId: Long): WaveformPreview? = cache[trackId]?.preview

    fun detail(trackId: Long): WaveformDetail? = cache[trackId]?.detail

    fun beatGrid(trackId: Long): BeatGrid? = cache[trackId]?.beatGrid

    fun isSampleTrack(trackId: Long?): Boolean = trackId != null && cache.containsKey(trackId)
}
