package com.miokzz.horizoncam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2

class GravityRollSensor(
    context: Context,
    private val onRoll: (Float) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var filteredX = 0f
    private var filteredY = -9.81f
    private var initialized = false

    fun start() {
        gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val alpha = if (event.sensor.type == Sensor.TYPE_GRAVITY) 0.35f else 0.08f
        val x = event.values[0]
        val y = event.values[1]

        if (!initialized) {
            filteredX = x
            filteredY = y
            initialized = true
        } else {
            filteredX += alpha * (x - filteredX)
            filteredY += alpha * (y - filteredY)
        }

        val degrees = Math.toDegrees(atan2(filteredX.toDouble(), -filteredY.toDouble())).toFloat()
        onRoll(degrees)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
