package com.miokzz.horizoncam.sensors

import com.miokzz.horizoncam.stabilization.AngleMath
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Sensor timestamp based complementary fusion. Roll is UNWRAPPED internally,
 * so a phone rolling across +179/-179 never jumps 358 degrees.
 * At weak projected gravity it continues integrating angular velocity.
 */
class RollFusion(
    private val gravityGain: Double = 0.21,
    private val minimumGravity: Double = 1.7
) {
    data class Reading(val timestampNs: Long, val rollDegrees: Double, val gravityReliable: Boolean)

    private var angle = 0.0
    private var initialized = false
    private var lastGyroNs = 0L
    private var lastNs = 0L
    var gravityValid: Boolean = false
        private set

    fun onGyroscope(zRadPerSecond: Double, timestampNs: Long): Reading? {
        if (!zRadPerSecond.isFinite() || timestampNs <= 0L) return null
        if (lastGyroNs > 0L && timestampNs > lastGyroNs) {
            val deltaSeconds = (timestampNs - lastGyroNs) / 1e9
            if (initialized && deltaSeconds <= 0.12) {
                angle -= Math.toDegrees(zRadPerSecond * deltaSeconds)
            }
        }
        lastGyroNs = timestampNs
        return if (initialized && timestampNs >= lastNs) {
            lastNs = timestampNs
            Reading(timestampNs, angle, gravityValid)
        } else null
    }

    fun onGravity(x: Double, y: Double, timestampNs: Long): Reading? {
        if (!x.isFinite() || !y.isFinite() || timestampNs <= 0L) return null
        val strength = hypot(x, y)
        gravityValid = strength >= minimumGravity
        if (!gravityValid) return null

        val measured = AngleMath.gravityRoll(x, y)
        if (!initialized) {
            angle = measured
            initialized = true
        } else {
            angle += AngleMath.delta(angle, measured) * gravityGain
        }
        if (timestampNs <= lastNs) return null
        lastNs = timestampNs
        return Reading(timestampNs, angle, true)
    }

    fun currentDegrees(): Double = angle
}
