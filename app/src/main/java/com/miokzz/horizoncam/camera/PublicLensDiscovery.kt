package com.miokzz.horizoncam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraFilter
import androidx.camera.lifecycle.ProcessCameraProvider

/**
 * Finds a wider REAR camera only if CameraX actually exposes it as a selectable
 * camera. Physical-only IDs in a logical multicamera are deliberately not
 * advertised as independently accessible.
 */
object PublicLensDiscovery {
    data class WideLens(val id: String, val selector: CameraSelector, val fov: Double)

    fun findAccessibleUltrawide(
        context: Context,
        provider: ProcessCameraProvider
    ): WideLens? {
        val cameraManager =
            context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val primaryInfo = provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
        val primaryId = Camera2CameraInfo.from(primaryInfo).cameraId
        val primaryFov = fov(cameraManager, primaryId) ?: return null

        val candidate = provider.availableCameraInfos.mapNotNull { info ->
            runCatching {
                val c2 = Camera2CameraInfo.from(info)
                val id = c2.cameraId
                if (id == primaryId) return@runCatching null
                val facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) return@runCatching null
                val degrees = fov(cameraManager, id) ?: return@runCatching null
                if (degrees <= primaryFov * 1.25) return@runCatching null
                id to degrees
            }.getOrNull()
        }.maxByOrNull { it.second } ?: return null

        val wideId = candidate.first
        val selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter(CameraFilter { cameras ->
                cameras.filter { info ->
                    runCatching { Camera2CameraInfo.from(info).cameraId == wideId }
                        .getOrDefault(false)
                }
            })
            .build()

        return WideLens(wideId, selector, candidate.second)
    }

    private fun fov(manager: CameraManager, id: String): Double? {
        val chars = manager.getCameraCharacteristics(id)
        val f = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull()?.toDouble() ?: return null
        val sensorWidth = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?.width?.toDouble() ?: return null
        if (f <= 0.0 || sensorWidth <= 0.0) return null
        return Math.toDegrees(2 * kotlin.math.atan(sensorWidth / (2 * f)))
    }
}
