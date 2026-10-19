package com.beatlinkmonitor.outputs.grandma

import com.beatlinkmonitor.model.MasterClockState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CommandTemplateEngineTest {

    private val master = MasterClockState(
        masterPlayerNumber = 1,
        trackBpm = 140.0,
        effectiveBpm = 141.02,
        valid = true,
    )

    @Test
    fun `replaces bpm placeholder with two decimals`() {
        val result = CommandTemplateEngine.render("SpecialMaster 3.1 At {BPM}", master)
        assertEquals("SpecialMaster 3.1 At 141.02", result)
    }

    @Test
    fun `replaces bpm with one decimal and integer variants`() {
        assertEquals("141.0", CommandTemplateEngine.render("{BPM_1}", master))
        assertEquals("141", CommandTemplateEngine.render("{BPM_INT}", master))
    }

    @Test
    fun `replaces master player, title, artist and track id`() {
        val result = CommandTemplateEngine.render(
            "P={MASTER_PLAYER} T={TITLE} A={ARTIST} ID={TRACK_ID}",
            master,
            trackId = 12345L,
            title = "Strobe",
            artist = "Deadmau5",
        )
        assertEquals("P=1 T=Strobe A=Deadmau5 ID=12345", result)
    }

    @Test
    fun `renders login template with username and password`() {
        val result = CommandTemplateEngine.render(
            "login \"{USERNAME}\" \"{PASSWORD}\"",
            MasterClockState.INVALID,
            username = "user",
            password = "secret",
        )
        assertEquals("login \"user\" \"secret\"", result)
    }

    @Test
    fun `missing values render as empty strings, never invented`() {
        val result = CommandTemplateEngine.render("T={TITLE} A={ARTIST}", MasterClockState.INVALID)
        assertEquals("T= A=", result)
    }

    @Test
    fun `redacts password from a rendered command for logging`() {
        val rendered = "login \"user\" \"secret\""
        val redacted = CommandTemplateEngine.redactForLogging(rendered, "secret")
        assertEquals("login \"user\" \"***\"", redacted)
        assertFalse(redacted.contains("secret"))
    }
}
