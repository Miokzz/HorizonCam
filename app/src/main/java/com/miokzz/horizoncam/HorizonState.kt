package com.miokzz.horizoncam

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

/**
 * The output's orientation is selected automatically, then frozen for recording.
 * Phone roll remains gravity-relative throughout the take.
 */
class HorizonState {
    private val roll = AtomicReference(0f)
    private val outputCardinal = AtomicReference(0f)
    private val initialized = AtomicBoolean(false)
    private val recording = AtomicBoolean(false)
    private val enabled = AtomicBoolean(true)
    private val sign = AtomicReference(1f)

    fun updateRoll(degrees: Float) {
        if (!degrees.isFinite()) return
        val next = wrap(degrees)
        roll.set(next)
        if (initialized.compareAndSet(false, true)) {
            outputCardinal.set(nearestCardinal(next))
        }
        // A rotation of the device is NOT a new video orientation.
        // The output orientation is changed only when the Android display
        // actually changes its orientation (via alignToScreenOrientation).
        // This avoids snapping the preview at arbitrary roll thresholds.
    }

    fun alignToScreenOrientation() {
        if (!recording.get()) outputCardinal.set(nearestCardinal(roll.get()))
    }

    fun startRecording() {
        alignToScreenOrientation()
        recording.set(true)
    }

    fun stopRecording() {
        recording.set(false)
        // Keep the last stabilized output until the UI actually rotates.
        // Aligning immediately here can flip the view by 180 degrees.
    }

    fun isEnabled() = enabled.get()
    fun toggleEnabled(): Boolean = (!enabled.get()).also { enabled.set(it) }
    fun toggleDirection(): Float = (-sign.get()).also { sign.set(it) }
    fun currentRoll() = roll.get()

    fun correctionDegrees(): Float {
        if (!enabled.get()) return 0f
        return wrap((outputCardinal.get() - roll.get()) * sign.get())
    }

    fun fixedCropScale(aspectRatio: Float = 16f / 9f): Float {
        if (!enabled.get()) return 1f
        val a = max(aspectRatio, 1f / aspectRatio).coerceAtLeast(1f)
        return (sqrt(1f + a * a) * 1.015f).coerceIn(1f, 2.20f)
    }

    private fun nearestCardinal(value: Float): Float = wrap(round(value / 90f) * 90f)

    private fun wrap(value: Float): Float {
        var v = value
        while (v > 180f) v -= 360f
        while (v < -180f) v += 360f
        return v
    }
}
