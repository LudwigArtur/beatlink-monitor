package com.beatlinkmonitor.outputs.ableton

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Regression test for a real bug found during manual verification: under a
 * comma-decimal locale (e.g. German), naive `"%.4f".format(value)` sends
 * "128,0000" to Carabiner, which rejects it with `bad-bpm`. All Carabiner
 * commands must use a period regardless of the host's default locale.
 */
class AbletonLinkLocaleFormattingTest {

    private lateinit var originalLocale: Locale

    @BeforeTest
    fun saveLocale() {
        originalLocale = Locale.getDefault()
    }

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `bpm formatting uses a period even under a comma-decimal default locale`() {
        Locale.setDefault(Locale.GERMANY)
        val formatted = fmt(128.0, 4)
        assertEquals("128.0000", formatted)
        assertFalse(formatted.contains(','))
    }

    @Test
    fun `quantum formatting uses a period even under a comma-decimal default locale`() {
        Locale.setDefault(Locale.GERMANY)
        val formatted = fmt(4.0, 4)
        assertEquals("4.0000", formatted)
        assertFalse(formatted.contains(','))
    }
}
