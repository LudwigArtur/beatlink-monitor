package com.beatlinkmonitor.outputs.grandma

/** How long to wait before the next connection attempt: 3 s after the first failure, then doubling up to 30 s. */
object ReconnectDelay {
    const val FIRST_MS = 3_000L
    const val MAX_MS = 30_000L

    fun afterFailures(failures: Int): Long {
        if (failures <= 1) return FIRST_MS
        var wait = FIRST_MS
        repeat(failures - 1) { wait = minOf(MAX_MS, wait * 2) }
        return wait
    }
}
