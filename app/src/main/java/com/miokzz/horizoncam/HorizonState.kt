package com.miokzz.horizoncam

import com.miokzz.horizoncam.sensors.OrientationHistory
import com.miokzz.horizoncam.stabilization.AngleMath
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

enum class StabilizationMode { OFF, STEADY, HORIZONTAL_LOCK }

data class FrameGeometry(
    val rotationDegrees: Float,
    val cropScale: Float,
    val timestampValid: Boolean,
    val sensorAgeMs: Float
)

/** No UI/display configuration may change the landscape gravity reference. */
class HorizonState {
    private val history = OrientationHistory()
    private val mode = AtomicReference(StabilizationMode.HORIZONTAL_LOCK)
    private val inverted = AtomicBoolean(false)
    private val recording = AtomicBoolean(false)
    private val cameraTimebaseReliable = AtomicBoolean(true)

    fun updateRoll(degrees: Float) {
        pushRoll(System.nanoTime(), degrees)
    }

    fun pushRoll(timestampNs: Long, degrees: Float) {
        if (degrees.isFinite()) history.append(timestampNs, degrees.toDouble())
    }

    fun setCameraRealtimeTimestamp(reliable: Boolean) {
        cameraTimebaseReliable.set(reliable)
    }

    fun currentRoll() = AngleMath.wrap(history.latest()).toFloat()
    fun mode() = mode.get()
    fun setMode(value: StabilizationMode) { mode.set(value) }
    fun isEnabled() = mode() == StabilizationMode.HORIZONTAL_LOCK
    fun toggleEnabled(): Boolean {
        val newMode = if (isEnabled()) StabilizationMode.OFF else StabilizationMode.HORIZONTAL_LOCK
        setMode(newMode)
        return isEnabled()
    }

    fun toggleDirection(): Float {
        inverted.set(!inverted.get())
        return if (inverted.get()) -1f else 1f
    }

    fun startRecording() { recording.set(true) }
    fun stopRecording() { recording.set(false) }
    fun isRecording() = recording.get()
    fun isLandscapePose(): Boolean = abs(abs(currentRoll()) - 90f) < 42f

    fun correctionDegrees(): Float = degreesForRoll(history.latest())

    fun frameGeometry(frameTimeNs: Long, aspectRatio: Float = 16f / 9f): FrameGeometry {
        if (!isEnabled()) return FrameGeometry(0f, 1f, true, 0f)
        val sample = history.sample(frameTimeNs, cameraTimebaseReliable.get())
        return FrameGeometry(
            degreesForRoll(sample.rollDegrees),
            AngleMath.safeFixedCrop(aspectRatio.toDouble()).toFloat(),
            sample.validTimestamp,
            sample.ageMs.toFloat()
        )
    }

    /** Camera2 + CameraX renders landscape using the phone's +90 gravity roll. */
    private fun degreesForRoll(roll: Double): Float {
        if (!isEnabled()) return 0f
        val answer = AngleMath.wrap(90.0 - roll)
        return (if (inverted.get()) -answer else answer).toFloat()
    }

    fun fixedCropScale(aspectRatio: Float = 16f / 9f) =
        if (isEnabled()) AngleMath.safeFixedCrop(aspectRatio.toDouble()).toFloat() else 1f
}
