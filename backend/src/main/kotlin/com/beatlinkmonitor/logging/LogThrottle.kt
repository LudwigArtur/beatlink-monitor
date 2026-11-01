package com.beatlinkmonitor.logging

import java.util.concurrent.ConcurrentHashMap

/**
 * Lets a log call that can repeat many times per second (a failing socket, an unreachable console) through at most
 * once per [intervalMs] for each key and counts what was left out, so the log keeps the first occurrence plus a
 * "(N more)" hint instead of tens of thousands of identical lines and stack traces.
 */
class LogThrottle(
    private val intervalMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(var lastLoggedAt: Long, var suppressed: Int)

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * Returns null when the message for [key] was logged less than the interval ago, otherwise the number of
     * messages that were suppressed since the last logged one (0 for the first).
     */
    fun tryAcquire(key: String): Int? {
        val now = clock()
        val entry = entries.computeIfAbsent(key) { Entry(Long.MIN_VALUE, 0) }
        synchronized(entry) {
            if (entry.lastLoggedAt != Long.MIN_VALUE && now - entry.lastLoggedAt < intervalMs) {
                entry.suppressed++
                return null
            }
            val suppressed = entry.suppressed
            entry.lastLoggedAt = now
            entry.suppressed = 0
            return suppressed
        }
    }

    /** Forgets [key], so the next message for it is logged at once (for example after the problem went away). */
    fun reset(key: String) {
        entries.remove(key)
    }

    companion object {
        /** "text" or "text (N similar messages suppressed)". */
        fun withSuppressed(text: String, suppressed: Int): String =
            if (suppressed > 0) "$text ($suppressed similar messages suppressed)" else text
    }
}
