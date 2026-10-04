package com.miokzz.horizoncam

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

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
     * Uniform zoom needed so a rotated rectangular frame keeps covering
     * the entire output viewport without black corners.
     */
    fun safeCropScale(aspectRatio: Float = 16f / 9f): Float {
        val radians = Math.toRadians(correctionDegrees().toDouble())
        val c = abs(cos(radians)).toFloat()
        val s = abs(sin(radians)).toFloat()
        return max(c + s / aspectRatio, c + s * aspectRatio).coerceIn(1f, 2.25f)
    }

    private fun wrap(value: Float): Float {
        var v = value
        while (v > 180f) v -= 360f
        while (v < -180f) v += 360f
        return v
    }
}
