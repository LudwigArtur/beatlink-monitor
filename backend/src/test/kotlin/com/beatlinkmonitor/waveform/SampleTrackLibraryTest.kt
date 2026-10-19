package com.beatlinkmonitor.waveform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SampleTrackLibraryTest {

    @Test
    fun `exposes exactly the two bundled sample tracks with real metadata`() {
        assertEquals(2, SampleTrackLibrary.TRACKS.size)
        val titles = SampleTrackLibrary.TRACKS.map { it.title }
        assertTrue(titles.contains("Concrete Angel (Original Mix)"))
        assertTrue(titles.contains("Transcendence"))
    }

    @Test
    fun `isSampleTrack recognizes bundled track ids and rejects others`() {
        for (track in SampleTrackLibrary.TRACKS) {
            assertTrue(SampleTrackLibrary.isSampleTrack(track.trackId))
        }
        assertTrue(!SampleTrackLibrary.isSampleTrack(123456L))
        assertTrue(!SampleTrackLibrary.isSampleTrack(null))
    }

    @Test
    fun `loads a real decodable preview and detail waveform for each sample track`() {
        for (track in SampleTrackLibrary.TRACKS) {
            val preview = SampleTrackLibrary.preview(track.trackId)
            val detail = SampleTrackLibrary.detail(track.trackId)
            assertNotNull(preview, "preview missing for ${track.title}")
            assertNotNull(detail, "detail missing for ${track.title}")

            assertTrue(preview.isColor)
            assertTrue(detail.isColor)
            assertTrue(preview.segmentCount > 0)
            assertTrue(detail.frameCount > 0)

            val (heights, colors) = WaveformPixelDecoder.decodePreview(preview)
            assertTrue(heights.isNotEmpty())
            assertNotNull(colors)
            assertTrue(colors.all { it.matches(Regex("#[0-9a-f]{6}")) })
        }
    }

    @Test
    fun `detail frame count matches the track's real duration (150 half-frames per second)`() {
        for (track in SampleTrackLibrary.TRACKS) {
            val detail = SampleTrackLibrary.detail(track.trackId)!!
            val impliedDurationMs = detail.frameCount * 1000L / 150L
            // Allow a little slack since the bundled duration is rounded to whole seconds.
            assertTrue(
                kotlin.math.abs(impliedDurationMs - track.durationMs) < 1000,
                "expected ~${track.durationMs}ms, frame count implies ${impliedDurationMs}ms",
            )
        }
    }

    @Test
    fun `decodeDetailWindow slices the real sample track waveform correctly`() {
        val track = SampleTrackLibrary.TRACKS.first()
        val detail = SampleTrackLibrary.detail(track.trackId)!!

        val (heights, colors) = WaveformPixelDecoder.decodeDetailWindow(
            detail,
            positionMs = track.durationMs / 2,
            beforeMs = 5_625L,
            afterMs = 9_375L,
            columns = 600,
        )
        assertEquals(600, heights.size)
        assertNotNull(colors)
        assertEquals(600, colors.size)
    }

    @Test
    fun `loads a real decodable beat grid for each sample track with correct bar cycling`() {
        for (track in SampleTrackLibrary.TRACKS) {
            val grid = SampleTrackLibrary.beatGrid(track.trackId)
            assertNotNull(grid, "beat grid missing for ${track.title}")
            assertTrue(grid.beatCount > 0)

            val (times, beats) = BeatGridDecoder.decode(grid)
            assertEquals(grid.beatCount, times.size)
            assertEquals(grid.beatCount, beats.size)
            // First beat is a downbeat at (or very near) the start of the track.
            assertEquals(1, beats.first())
            assertTrue(times.first() < 1000L)
            // Beat times must be strictly increasing.
            assertTrue(times.zipWithNext().all { (a, b) -> b > a })
            // Bar position must always be 1..4.
            assertTrue(beats.all { it in 1..4 })
        }
    }
}
