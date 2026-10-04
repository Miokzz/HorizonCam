package com.miokzz.horizoncam

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 360-degree roll lock anchored to a LANDSCAPE video canvas.
 *
 * Orientation of the Android activity must never re-anchor this state. In
 * particular, no auto-snapping to 0/90/180/270 during a full phone revolution.
 */
class HorizonState {
    private val roll = AtomicReference(0f)
    private val enabled = AtomicBoolean(true)
    private val inverted = AtomicBoolean(false)
    private val recording = AtomicBoolean(false)

    fun updateRoll(degrees: Float) {
        if (degrees.isFinite()) roll.set(wrap(degrees))
    }

    fun currentRoll(): Float = roll.get()
    fun isEnabled(): Boolean = enabled.get()
    fun isRecording(): Boolean = recording.get()
    fun toggleEnabled(): Boolean = (!enabled.get()).also { enabled.set(it) }

    fun toggleDirection(): Float {
        inverted.set(!inverted.get())
        return if (inverted.get()) -1f else 1f
    }

    fun startRecording() {
        recording.set(true)
    }

    fun stopRecording() {
        recording.set(false)
    }

    fun isLandscapePose(): Boolean = abs(abs(roll.get()) - 90f) <= 40f

    /**
     * Device physical roll is sampled continuously and relative to gravity.
     * The output coordinate system NEVER changes during portrait/UI rotations.
     * 90 degrees is the landscape-zero device angle (normal landscape grip).
     */
    fun correctionDegrees(): Float {
        if (!enabled.get()) return 0f
        val correction = wrap(90f - roll.get())
        return if (inverted.get()) wrap(-correction) else correction
    }

    /**
     * Same fixed overscan at every roll angle, so field of view never pumps.
     * Not a substitute for an actual wider optical input like S26 Super Steady.
     */
    fun fixedCropScale(aspectRatio: Float = 16f / 9f): Float {
        if (!enabled.get()) return 1f
        val a = max(aspectRatio, 1f / aspectRatio).coerceAtLeast(1f)
        return (sqrt(1f + a * a) * 1.015f).coerceIn(1f, 2.20f)
    }

    private fun wrap(value: Float): Float {
        var v = value
        while (v > 180f) v -= 360f
        while (v < -180f) v += 360f
        return v
    }
}
