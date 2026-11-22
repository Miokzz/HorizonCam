package com.miokzz.horizoncam.sensors

import com.miokzz.horizoncam.stabilization.AngleMath
import kotlin.math.abs

/** Monotonic BOOTTIME sensor samples. No extrapolation across unavailable history. */
class OrientationHistory(private val capacity: Int = 512) {
    data class FrameSample(
        val rollDegrees: Double,
        val validTimestamp: Boolean,
        val ageMs: Double,
        val hasOrientation: Boolean = true
    )
    private data class Sample(val t: Long, val roll: Double)
    private val samples = ArrayDeque<Sample>()
    init { require(capacity >= 2) }

    @Synchronized fun clear() = samples.clear()
    @Synchronized fun hasSamples() = samples.isNotEmpty()
    @Synchronized fun isFresh(nowNs: Long): Boolean {
        val latest = samples.lastOrNull() ?: return false
        return nowNs >= latest.t && nowNs - latest.t <= MAX_GAP_NS
    }

    @Synchronized fun append(timestampNs: Long, wrappedOrUnwrappedRoll: Double) {
        if (timestampNs <= 0L || !wrappedOrUnwrappedRoll.isFinite()) return
        val last = samples.lastOrNull()
        if (last != null && timestampNs <= last.t) return
        val continuous = if (last == null) wrappedOrUnwrappedRoll
            else last.roll + AngleMath.delta(last.roll, wrappedOrUnwrappedRoll)
        samples.addLast(Sample(timestampNs, continuous))
        while (samples.size > capacity) samples.removeFirst()
    }

    @Synchronized fun latest(): Double = samples.lastOrNull()?.roll ?: 0.0

    @Synchronized fun sample(
        timestampNs: Long,
        comparableTimebase: Boolean,
        arrivalTimeNs: Long = timestampNs
    ): FrameSample {
        val last = samples.lastOrNull() ?: return FrameSample(0.0, false, Double.POSITIVE_INFINITY, false)
        // Unknown camera timestamps cannot be compared to Android sensor BOOTTIME.
        // Report the latest reading honestly; arrival is supplied from elapsedRealtimeNanos.
        if (!comparableTimebase || timestampNs <= 0L) {
            return FrameSample(last.roll, false, abs(arrivalTimeNs - last.t) / 1e6)
        }
        val first = samples.first()
        if (timestampNs < first.t) {
            return FrameSample(first.roll, false, (first.t - timestampNs) / 1e6)
        }
        if (timestampNs > last.t) {
            // A held latest value is useful for rendering, but is NOT synchronized.
            return FrameSample(last.roll, false, (timestampNs - last.t) / 1e6)
        }
        if (timestampNs == last.t) return FrameSample(last.roll, true, 0.0)
        var newer = last
        for (index in samples.lastIndex - 1 downTo 0) {
            val older = samples[index]
            if (older.t == timestampNs) return FrameSample(older.roll, true, 0.0)
            if (older.t < timestampNs) {
                val span = newer.t - older.t
                val f = (timestampNs - older.t).toDouble() / span
                val nearestAge = minOf(timestampNs - older.t, newer.t - timestampNs) / 1e6
                if (span > MAX_GAP_NS) return FrameSample(older.roll, false, nearestAge)
                return FrameSample(older.roll + (newer.roll - older.roll) * f, true, nearestAge)
            }
            newer = older
        }
        return FrameSample(first.roll, false, (first.t - timestampNs) / 1e6)
    }

    companion object {
        // 100 ms tolerates 30/50 Hz fallback sensors, without claiming stale lock.
        const val MAX_GAP_NS = 100_000_000L
    }
}
