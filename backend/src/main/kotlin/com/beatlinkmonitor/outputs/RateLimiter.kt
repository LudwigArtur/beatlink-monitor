package com.beatlinkmonitor.outputs

/**
 * Enforces "send on change" + "minimum change" + "maximum send rate"
 * simultaneously, so the application never floods an output with one command
 * per incoming Pro DJ Link packet (requirements specification §13.5).
 *
 * Pure and side-effect free (besides its own mutable bookkeeping) so it is
 * trivially unit-testable (requirements specification §39.1).
 */
class RateLimiter(
    private var sendOnChange: Boolean,
    private var minimumChange: Double,
    private var maximumRateHz: Double,
) {
    private var lastSentValue: Double? = null
    private var lastSentAtMs: Long = 0L

    fun configure(sendOnChange: Boolean, minimumChange: Double, maximumRateHz: Double) {
        this.sendOnChange = sendOnChange
        this.minimumChange = minimumChange
        this.maximumRateHz = maximumRateHz
    }

    /**
     * Returns true if [value] should be sent right now, given [nowMs]. When it
     * returns true, it also records the value/time as "sent" - callers must
     * only call this once per candidate send.
     */
    fun shouldSend(value: Double, nowMs: Long): Boolean {
        val previous = lastSentValue
        if (sendOnChange && previous != null && kotlin.math.abs(value - previous) < minimumChange) {
            return false
        }
        val minIntervalMs = if (maximumRateHz > 0) (1000.0 / maximumRateHz).toLong() else 0L
        if (previous != null && nowMs - lastSentAtMs < minIntervalMs) {
            return false
        }
        lastSentValue = value
        lastSentAtMs = nowMs
        return true
    }

    fun reset() {
        lastSentValue = null
        lastSentAtMs = 0L
    }
}
