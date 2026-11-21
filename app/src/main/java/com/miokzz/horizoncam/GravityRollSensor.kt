package com.miokzz.horizoncam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.miokzz.horizoncam.sensors.RollFusion
import kotlin.math.exp

/** Public fused attitude preferred; gravity/accelerometer use the identical world-up sign. */
class GravityRollSensor(
    context: Context,
    private val onReading: (RollFusion.Reading) -> Unit
) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val source = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val fusion = RollFusion()
    private val rotation = FloatArray(9)
    private val sensorThread = HandlerThread("HorizonCam-Inertial").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)
    private var filteredX = 0.0
    private var filteredY = 0.0
    private var previousAccelNs = 0L
    @Volatile private var activated = false
    private var startedAtNs = 0L
    val isAvailable: Boolean get() = source != null
    val sourceName: String get() = source?.name ?: "Sensor de orientação indisponível"

    @Synchronized fun start() {
        if (activated) return
        fusion.reset()
        previousAccelNs = 0L
        startedAtNs = android.os.SystemClock.elapsedRealtimeNanos()
        // SensorManager registration is synchronous. Samples are dispatched on
        // our sensor Handler, while this monitor serializes start/stop/events.
        activated = source?.let {
            manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, sensorHandler)
        } ?: false
    }

    @Synchronized fun stop() {
        activated = false
        manager.unregisterListener(this)
    }

    fun release() {
        stop()
        sensorThread.quitSafely()
    }

    @Synchronized override fun onSensorChanged(event: SensorEvent) {
        if (!activated || event.timestamp < startedAtNs) return
        val down = when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                // R maps device vectors to earth. R^T * (0,0,+g) is
                // earth UP in device axes: +Y in natural portrait, +X
                // when the natural-portrait phone is turned CCW landscape.
                Pair(-rotation[6].toDouble() * 9.81, -rotation[7].toDouble() * 9.81)
            }
            Sensor.TYPE_GRAVITY -> Pair(-event.values[0].toDouble(), -event.values[1].toDouble())
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0].toDouble()
                val y = event.values[1].toDouble()
                val previous = previousAccelNs
                if (previous == 0L || event.timestamp - previous > 500_000_000L) {
                    filteredX = x
                    filteredY = y
                } else {
                    // Fallback only: low-pass acceleration, using actual sample interval.
                    val gain = 1.0 - exp(-(event.timestamp - previous).coerceAtLeast(0L) / 1e9 / 0.10)
                    filteredX += gain * (x - filteredX)
                    filteredY += gain * (y - filteredY)
                }
                previousAccelNs = event.timestamp
                Pair(-filteredX, -filteredY)
            }
            else -> return
        }
        fusion.onGravity(down.first, down.second, event.timestamp)?.let(onReading)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
