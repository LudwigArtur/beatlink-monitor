package com.beatlinkmonitor.waveform

import org.deepsymmetry.beatlink.CdjStatus
import org.deepsymmetry.beatlink.data.DataReference
import org.deepsymmetry.beatlink.data.WaveformDetail
import org.deepsymmetry.beatlink.data.WaveformPreview
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Validates our JSON conversion layer against `beat-link`'s own waveform
 * classes, using small, entirely self-authored synthetic byte buffers (never
 * data captured from a real/copyrighted track) that exercise the documented
 * color and monochrome segment formats.
 */
class WaveformPixelDecoderTest {

    private val reference = DataReference(1, CdjStatus.TrackSourceSlot.USB_SLOT, 42)

    @Test
    fun `decodes a monochrome (classic) preview using beat-link's own accessor`() {
        // Non-color preview format: 2 bytes per segment - [height(0-31), intensity].
        val bytes = byteArrayOf(10, 0, 31, 7, 0, 3)
        val preview = WaveformPreview(reference, ByteBuffer.wrap(bytes), false)

        val (heights, colors) = WaveformPixelDecoder.decodePreview(preview)

        assertEquals(listOf(10, 31, 0), heights)
        assertNull(colors)
    }

    @Test
    fun `decodes a color (RGB) preview using beat-link's own accessor`() {
        // Color preview format: 6 bytes per segment - [_, _, _, r, g, b] scaled by height.
        val segment = byteArrayOf(0, 0, 0, 10, 20, 30)
        val preview = WaveformPreview(reference, ByteBuffer.wrap(segment), true)

        val (heights, colors) = WaveformPixelDecoder.decodePreview(preview)

        assertEquals(1, heights.size)
        assertEquals(30, heights[0]) // back height = max(r, g, b) = 30
        assertTrue(colors != null && colors.size == 1)
        assertTrue(colors!![0].startsWith("#"))
    }

    @Test
    fun `decodes a color detail buffer into heights and colors`() {
        // Color detail format: 2 bytes per frame - [height, colorIndex].
        val bytes = ByteArray(20) { i -> if (i % 2 == 0) (i / 2).toByte() else 4 }
        val detail = WaveformDetail(reference, ByteBuffer.wrap(bytes), true)

        val (heights, colors) = WaveformPixelDecoder.decodeDetail(detail, maxColumns = 100)

        assertEquals(10, heights.size)
        assertTrue(colors != null && colors.size == 10)
    }

    @Test
    fun `decodeDetail downsamples long buffers to at most maxColumns`() {
        val bytes = ByteArray(2000) { 5 }
        val detail = WaveformDetail(reference, ByteBuffer.wrap(bytes), false)

        val (heights, colors) = WaveformPixelDecoder.decodeDetail(detail, maxColumns = 100)

        assertTrue(heights.size <= 101)
        assertNull(colors)
    }
}
