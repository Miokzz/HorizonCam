package com.miokzz.horizoncam

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.sqrt

class HorizonState {
    private val currentRoll = AtomicReference(0f)
    private val referenceRoll = AtomicReference<Float?>(null)
    private val enabled = AtomicBoolean(true)
    private val direction = AtomicReference(1f)

    fun updateRoll(degrees: Float) {
        currentRoll.set(wrap(degrees))
        if (referenceRoll.get() == null) {
            referenceRoll.compareAndSet(null, currentRoll.get())
        }
    }

    fun currentRollDegrees(): Float = currentRoll.get()

    fun recalibrate() {
        referenceRoll.set(currentRoll.get())
    }

    fun setEnabled(value: Boolean) {
        enabled.set(value)
    }

    fun toggleDirection(): Float {
        val next = -direction.get()
        direction.set(next)
        return next
    }

    fun correctionDegrees(): Float {
        if (!enabled.get()) return 0f
        val reference = referenceRoll.get() ?: return 0f
        return wrap((reference - currentRoll.get()) * direction.get())
    }

    /**
     * Constant overscan for a full 360-degree roll.
     *
     * Using a fixed value prevents the "breathing" FOV from dynamic crop.
     * For a rectangular viewport, the worst case over every angle is:
     * sqrt(1 + max(aspect, 1/aspect)^2).
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
