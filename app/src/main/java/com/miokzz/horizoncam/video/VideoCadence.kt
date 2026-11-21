package com.miokzz.horizoncam.video

/** Evidence from encoded presentation timestamps, not a requested/metadata-only FPS label. */
data class VideoCadence(
    val inspectedSamples: Int,
    val distinctPresentationTimes: Int,
    val duplicatePresentationTimes: Int,
    val inspectedDurationUs: Long,
    val averageFps: Double?,
    val medianIntervalUs: Long?,
    val longestIntervalUs: Long?,
    val sampleLimitReached: Boolean,
) {
    companion object {
        fun measure(
            timestampsUs: Collection<Long>,
            sampleLimitReached: Boolean = false,
        ): VideoCadence {
            val valid = timestampsUs.filter { it >= 0 }
            // Encoded B-frames can arrive in decode order. Measure presentation
            // cadence after sorting; do not falsely flag decode order as drops.
            val sorted = valid.distinct().sorted()
            val intervals = sorted.zipWithNext { a, b -> b - a }.sorted()
            val duration = if (sorted.size >= 2) sorted.last() - sorted.first() else 0L
            val fps = if (duration > 0) (sorted.size - 1) * 1_000_000.0 / duration else null
            val median =
                if (intervals.isEmpty()) null
                else if (intervals.size % 2 == 1) intervals[intervals.size / 2]
                else (intervals[intervals.size / 2 - 1] + intervals[intervals.size / 2]) / 2
            return VideoCadence(
                valid.size,
                sorted.size,
                valid.size - sorted.size,
                duration,
                fps,
                median,
                intervals.lastOrNull(),
                sampleLimitReached,
            )
        }
    }
}
