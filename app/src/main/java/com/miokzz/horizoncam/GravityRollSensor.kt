package com.miokzz.horizoncam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.miokzz.horizoncam.sensors.RollFusion

/**
 * Inertial sensor acquisition, separate from UI and GL threads.
 * Gyroscope predicts short-term motion; gravity fixes long-term drift.
 * A weak horizontal gravity projection keeps gyro prediction running.
 */
class GravityRollSensor(
    context: Context,
    private val onReading: (RollFusion.Reading) -> Unit
) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val attitude = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gravity = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val fusion = RollFusion()
    private val rotation = FloatArray(9)
    private val sensorThread = HandlerThread("HorizonCam-Inertial").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)
    private var gx = 0f
    private var gy = -9.81f
    private var activated = false

    fun start() {
        if (activated) return
        activated = true
        gyro?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler) }
        val correction = attitude ?: gravity
        correction?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, sensorHandler) }
    }

    fun stop() {
        activated = false
        manager.unregisterListener(this)
    }

    fun release() {
        stop()
        sensorThread.quitSafely()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val result = when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE ->
                fusion.onGyroscope(event.values[2].toDouble(), event.timestamp)
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                fusion.onGravity(
                    -rotation[6].toDouble() * 9.81,
                    -rotation[7].toDouble() * 9.81,
                    event.timestamp
                )
            }
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                val gain = if (event.sensor.type == Sensor.TYPE_GRAVITY) 0.5f else 0.085f
                gx += gain * (event.values[0] - gx)
                gy += gain * (event.values[1] - gy)
                fusion.onGravity(gx.toDouble(), gy.toDouble(), event.timestamp)
            }
            else -> null
        }
        result?.let(onReading)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
