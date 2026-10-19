package com.beatlinkmonitor.model

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * BPM related calculations. Internal computation must never round early
 * (requirements specification §7.2, §8, coding guideline #12); rounding only happens at
 * the point of display or output formatting.
 */
object BpmMath {
    private val PRECISION = MathContext(20)

    /**
     * Computes the effective BPM from a track's original BPM and the current
     * pitch fader percentage, using [BigDecimal] to avoid premature rounding.
     *
     * effectiveBpm = trackBpm * (1 + pitchPercent / 100)
     */
    fun effectiveBpm(trackBpm: Double, pitchPercent: Double): Double {
        val bpm = BigDecimal(trackBpm)
        val pitchFactor = BigDecimal.ONE.add(BigDecimal(pitchPercent).divide(BigDecimal(100), PRECISION))
        return bpm.multiply(pitchFactor, PRECISION).toDouble()
    }

    /** Rounds a BPM value for display, default two decimal places (requirements specification §7.3). */
    fun format(bpm: Double, decimals: Int = 2): Double =
        BigDecimal(bpm).setScale(decimals, RoundingMode.HALF_UP).toDouble()

    fun formatString(bpm: Double, decimals: Int): String =
        BigDecimal(bpm).setScale(decimals, RoundingMode.HALF_UP).toPlainString()

    fun intBpm(bpm: Double): Int =
        BigDecimal(bpm).setScale(0, RoundingMode.HALF_UP).toInt()
}
