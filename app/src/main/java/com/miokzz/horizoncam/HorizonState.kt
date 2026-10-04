package com.miokzz.horizoncam

import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sqrt

class HorizonState {
    private val currentRoll = AtomicReference(0f)
    private val enabled = AtomicBoolean(true)
    private val direction = AtomicReference(1f)
    private val landscapeAnchor = AtomicReference(90f)

    fun updateRoll(degrees: Float) {
        currentRoll.set(wrap(degrees))
    }

    fun setLandscapeAnchor(displayRotation: Int) {
        val anchor = when (displayRotation) {
            Surface.ROTATION_90 -> 90f
            Surface.ROTATION_270 -> -90f
            Surface.ROTATION_180 -> 180f
            else -> 90f
        }
        landscapeAnchor.set(anchor)
    }

    fun setEnabled(value: Boolean) {
        enabled.set(value)
    }

    fun isEnabled(): Boolean = enabled.get()

    fun toggleEnabled(): Boolean {
        val next = !enabled.get()
        enabled.set(next)
        return next
    }

    fun toggleDirection(): Float {
        val next = -direction.get()
        direction.set(next)
        return next
    }

    /**
     * Absolute device roll relative to our fixed landscape output.
     * No manual calibration is involved.
     */
    fun deviceToLandscapeDegrees(): Float {
        return wrap((landscapeAnchor.get() - currentRoll.get()) * direction.get())
    }

    /**
     * Rotation applied to the camera image when Horizon Lock is active.
     */
    fun correctionDegrees(): Float {
        return if (enabled.get()) deviceToLandscapeDegrees() else 0f
    }

    /**
     * Samsung-style UI orientation: snap to 0/90/180/270 instead of
     * continuously tilting every control with tiny hand movements.
     */
    fun hudRotationDegrees(): Float {
        val raw = deviceToLandscapeDegrees()
        return wrap(round(raw / 90f) * 90f)
    }

    /**
     * Fixed overscan for a full 360-degree roll.
     * The crop does not breathe while the phone rotates.
     */
    fun fixedCropScale(aspectRatio: Float = 16f / 9f): Float {
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
