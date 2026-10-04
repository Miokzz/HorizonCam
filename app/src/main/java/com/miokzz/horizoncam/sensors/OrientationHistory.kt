package com.miokzz.horizoncam.sensors

import com.miokzz.horizoncam.stabilization.AngleMath
import kotlin.math.abs

/**
 * Fixed-size monotonic timestamp history, sampled by the GPU at each frame's
 * actual camera timestamp. No UI callback is used for recording transforms.
 */
class OrientationHistory(private val capacity: Int = 512) {
    data class FrameSample(val rollDegrees: Double, val validTimestamp: Boolean, val ageMs: Double)
    private data class Sample(val t: Long, val roll: Double)
    private val samples = ArrayDeque<Sample>()

    init { require(capacity >= 2) }

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

    @Synchronized fun sample(timestampNs: Long, comparableTimebase: Boolean): FrameSample {
        val last = samples.lastOrNull() ?: return FrameSample(0.0, false, 1e9)
        val ageMs = (timestampNs - last.t) / 1e6
        if (!comparableTimebase || timestampNs <= 0L || abs(ageMs) > 1500.0) {
            return FrameSample(last.roll, false, ageMs)
        }
        val first = samples.first()
        if (timestampNs <= first.t) return FrameSample(first.roll, true, ageMs)
        if (timestampNs >= last.t) return FrameSample(last.roll, true, ageMs)

        // Newest frame timestamps are near the end; scan backwards.
        var newer = last
        for (sample in samples.reversed()) {
            if (sample.t <= timestampNs) {
                val span = newer.t - sample.t
                if (span <= 0L) return FrameSample(sample.roll, true, ageMs)
                val f = ((timestampNs - sample.t).toDouble() / span).coerceIn(0.0, 1.0)
                return FrameSample(sample.roll + (newer.roll - sample.roll) * f, true, ageMs)
            }
            newer = sample
        }
        return FrameSample(first.roll, true, ageMs)
    }
}
