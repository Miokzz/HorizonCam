package com.miokzz.horizoncam.sensors

/** Timestamp evidence, independent of the requested rate or event delivery bursts. */
class SensorCadence {
    private var firstNs = 0L
    private var lastNs = 0L
    private var intervals = 0L
    var longestIntervalNs = 0L
        private set

    fun reset() {
        firstNs = 0L
        lastNs = 0L
        intervals = 0L
        longestIntervalNs = 0L
    }

    fun append(timestampNs: Long) {
        if (timestampNs <= 0L || timestampNs <= lastNs) return
        if (lastNs == 0L) firstNs = timestampNs
        else {
            longestIntervalNs = maxOf(longestIntervalNs, timestampNs - lastNs)
            intervals++
        }
        lastNs = timestampNs
    }

    fun measuredHz(): Double? =
        if (intervals == 0L) null else intervals * 1e9 / (lastNs - firstNs)

    companion object {
        // Stay within Android's unprivileged 200 Hz rate. minDelay is a hardware
        // limit, not a guarantee of delivered cadence; measure the timestamps.
        fun requestedPeriodUs(minDelayUs: Int): Int = maxOf(5_000, minDelayUs)
    }
}
