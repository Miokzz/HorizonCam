package com.miokzz.horizoncam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.hypot

/** Gyro + accelerometer fusion if TYPE_GAME_ROTATION_VECTOR is available. */
class GravityRollSensor(
    context: Context,
    private val onRoll: (Float) -> Unit
) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val fused = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val fallback = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val active = fused ?: fallback
    private val rotation = FloatArray(9)
    private var filteredRoll = 0f
    private var hasRoll = false
    private var gx = 0f
    private var gy = -9.81f

    fun start() {
        active?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() { manager.unregisterListener(this) }

    override fun onSensorChanged(event: SensorEvent) {
        val x: Float
        val y: Float
        if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            x = -rotation[6] * 9.81f
            y = -rotation[7] * 9.81f
        } else {
            val alpha = if (event.sensor.type == Sensor.TYPE_GRAVITY) 0.38f else 0.08f
            gx += alpha * (event.values[0] - gx)
            gy += alpha * (event.values[1] - gy)
            x = gx
            y = gy
        }
        // Avoid unstable gravity-roll when the phone points straight up/down.
        if (hypot(x, y) < 1.3f) return
        val angle = Math.toDegrees(atan2(x.toDouble(), -y.toDouble())).toFloat()
        filteredRoll = wrap(if (!hasRoll) angle
            else filteredRoll + 0.72f * wrap(angle - filteredRoll))
        hasRoll = true
        onRoll(filteredRoll)
    }

    private fun wrap(value: Float): Float {
        var v = value
        while (v > 180f) v -= 360f
        while (v < -180f) v += 360f
        return v
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
