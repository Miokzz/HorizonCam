package com.miokzz.horizoncam.sensors

import com.miokzz.horizoncam.stabilization.AngleMath
import kotlin.math.hypot

/**
 * Projects world DOWN onto the device camera plane. Both rotation-vector and
 * gravity fallback acquisition must pass the same sign convention.
 * A fused rotation vector already includes three-axis gyro prediction; integrating
 * gyro Z again is incorrect when pitch/yaw are nonzero and adds drift.
 */
class RollFusion(
    private val gravityGain: Double = 1.0,
    private val minimumGravity: Double = 1.7
) {
    data class Reading(val timestampNs: Long, val rollDegrees: Double, val gravityReliable: Boolean)
    private var angle = 0.0
    private var initialized = false
    private var lastNs = 0L
    var gravityValid: Boolean = false
        private set

    fun reset() {
        angle = 0.0
        initialized = false
        lastNs = 0L
        gravityValid = false
    }

    fun onGravity(downX: Double, downY: Double, timestampNs: Long): Reading? {
        if (timestampNs <= lastNs || timestampNs <= 0L) return null
        if (!downX.isFinite() || !downY.isFinite()) return null
        lastNs = timestampNs
        gravityValid = hypot(downX, downY) >= minimumGravity
        // Pointing the optical axis along gravity has no observable horizon.
        // Hold the last orientation, but never publish a fresh fake reading.
        if (!gravityValid) return null
        val measured = AngleMath.gravityRoll(downX, downY)
        angle = if (!initialized) measured
            else angle + AngleMath.delta(angle, measured) * gravityGain.coerceIn(0.0, 1.0)
        initialized = true
        return Reading(timestampNs, angle, true)
    }

    fun currentDegrees(): Double = angle
}
