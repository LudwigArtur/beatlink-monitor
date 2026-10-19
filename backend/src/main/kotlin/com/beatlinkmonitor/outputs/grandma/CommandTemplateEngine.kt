package com.beatlinkmonitor.outputs.grandma

import com.beatlinkmonitor.model.BpmMath
import com.beatlinkmonitor.model.MasterClockState

/**
 * Resolves the placeholders documented in requirements specification §13.3 against the
 * current [MasterClockState] (and optional track metadata), without ever
 * rounding the underlying BPM before this point (coding guideline #12).
 */
object CommandTemplateEngine {

    fun render(
        template: String,
        master: MasterClockState,
        trackId: Long? = null,
        title: String? = null,
        artist: String? = null,
        username: String = "",
        password: String = "",
    ): String {
        val bpm = master.effectiveBpm ?: 0.0
        return template
            .replace("{BPM}", BpmMath.formatString(bpm, 2))
            .replace("{BPM_1}", BpmMath.formatString(bpm, 1))
            .replace("{BPM_INT}", BpmMath.intBpm(bpm).toString())
            .replace("{MASTER_PLAYER}", master.masterPlayerNumber?.toString() ?: "")
            .replace("{TRACK_ID}", trackId?.toString() ?: "")
            .replace("{TITLE}", title ?: "")
            .replace("{ARTIST}", artist ?: "")
            .replace("{USERNAME}", username)
            .replace("{PASSWORD}", password)
    }

    /** Redacts the password so templates can be safely logged (requirements specification §29.2). */
    fun redactForLogging(rendered: String, password: String): String =
        if (password.isNotEmpty()) rendered.replace(password, "***") else rendered
}
