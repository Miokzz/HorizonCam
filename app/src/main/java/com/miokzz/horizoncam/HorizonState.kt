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

    fun safeCropScale(aspectRatio: Float = 16f / 9f): Float {
        val radians = Math.toRadians(correctionDegrees().toDouble())
        val c = abs(cos(radians)).toFloat()
        val s = abs(sin(radians)).toFloat()
        val landscape = c + aspectRatio * s
        val portrait = c + (1f / aspectRatio) * s
        return max(landscape, portrait).coerceIn(1f, 2.05f)
    }

    private fun wrap(value: Float): Float {
        var v = value
        while (v > 180f) v -= 360f
        while (v < -180f) v += 360f
        return v
    }
}
