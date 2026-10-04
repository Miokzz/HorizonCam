package com.miokzz.horizoncam

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class HorizonState {
    private val currentRoll = AtomicReference(0f)
    private val referenceRoll = AtomicReference<Float?>(null)
    private val enabled = AtomicBoolean(true)

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

    fun isEnabled(): Boolean = enabled.get()

    fun correctionDegrees(): Float {
        if (!enabled.get()) return 0f
        val reference = referenceRoll.get() ?: return 0f
        return wrap(reference - currentRoll.get())
    }

    /**
     * Media3 MatrixTransformation works in normalized device coordinates:
     * x and y are both -1..1. Therefore the safe zoom for a rotated frame
     * is based on a square in NDC, not on the video's pixel aspect ratio.
     */
    fun safeCropScale(): Float {
        val radians = Math.toRadians(correctionDegrees().toDouble())
        val c = abs(cos(radians)).toFloat()
        val s = abs(sin(radians)).toFloat()
        return (c + s).coerceIn(1f, 1.43f)
    }

    private fun wrap(value: Float): Float {
        var v = value
        while (v > 180f) v -= 360f
        while (v < -180f) v += 360f
        return v
    }
}
