package com.beatlinkmonitor.prodjlink

/**
 * How long to wait before the metadata watchdog may restart the track loading subsystems again. A restart helps when
 * they wedged, but not when the players cannot be reached at all; restarting every few minutes then only throws away
 * whatever was loaded in the meantime. So every restart that did not fix anything doubles the wait, up to a limit,
 * and a healthy check starts over.
 */
class RestartBackoff(
    private val baseMs: Long = 3 * 60_000L,
    private val maxMs: Long = 30 * 60_000L,
) {
    private var failedRestarts = 0

    @Synchronized
    fun cooldownMs(): Long {
        var wait = baseMs
        repeat(failedRestarts) { wait = minOf(maxMs, wait * 2) }
        return wait
    }

    /** A restart was done; unless [recovered] is called before the next check it counts as one that did not help. */
    @Synchronized
    fun restarted() {
        failedRestarts++
    }

    /** Nothing is stuck any more. */
    @Synchronized
    fun recovered() {
        failedRestarts = 0
    }
}
