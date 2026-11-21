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
    val sensorAgeMs: Float,
    val orientationAvailable: Boolean = true
)

/** No UI/display configuration may change the landscape gravity reference. */
class HorizonState {
    private val history = OrientationHistory()
    private val mode = AtomicReference(StabilizationMode.HORIZONTAL_LOCK)
    private val inverted = AtomicBoolean(false)
    private val recording = AtomicBoolean(false)
    private val cameraTimebaseReliable = AtomicBoolean(false)

    fun updateRoll(degrees: Float) {
        pushRoll(android.os.SystemClock.elapsedRealtimeNanos(), degrees)
    }

    fun pushRoll(timestampNs: Long, degrees: Float) {
        if (degrees.isFinite()) history.append(timestampNs, degrees.toDouble())
    }

    fun setCameraRealtimeTimestamp(reliable: Boolean) {
        cameraTimebaseReliable.set(reliable)
    }

    fun hasOrientation() = history.hasSamples()
    fun orientationFresh(nowElapsedNs: Long) = history.isFresh(nowElapsedNs)
    fun clearOrientation() = history.clear()

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

    fun correctionDegrees(): Float = if (history.hasSamples()) degreesForRoll(history.latest()) else 0f

    fun frameGeometry(
        frameTimeNs: Long,
        aspectRatio: Float = 16f / 9f,
        arrivalTimeNs: Long = frameTimeNs
    ): FrameGeometry {
        if (!isEnabled()) return FrameGeometry(0f, 1f, true, 0f)
        val sample = history.sample(frameTimeNs, cameraTimebaseReliable.get(), arrivalTimeNs)
        return FrameGeometry(
            if (sample.hasOrientation) degreesForRoll(sample.rollDegrees) else 0f,
            AngleMath.safeFixedCrop(aspectRatio.toDouble()).toFloat(),
            sample.validTimestamp,
            sample.ageMs.toFloat(),
            sample.hasOrientation
        )
    }

    /**
     * Natural-portrait rear camera, fixed CameraX ROTATION_90 target.
     * Android UP=(+g,0) in CCW landscape, DOWN=(-g,0), roll=-90.
     * The base landscape image rotates that vector +90; undo its remaining
     * world-up angle. Display rotations and recording start never reset it.
     */
    private fun degreesForRoll(roll: Double): Float {
        if (!isEnabled()) return 0f
        val answer = AngleMath.wrap(-90.0 - roll)
        return (if (inverted.get()) -answer else answer).toFloat()
    }

    fun fixedCropScale(aspectRatio: Float = 16f / 9f) =
        if (isEnabled()) AngleMath.safeFixedCrop(aspectRatio.toDouble()).toFloat() else 1f
}
