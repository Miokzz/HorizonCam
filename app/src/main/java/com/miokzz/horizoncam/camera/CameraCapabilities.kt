package com.miokzz.horizoncam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.video.DynamicRange
import androidx.camera.video.Quality
import androidx.camera.video.Recorder

data class CameraCapabilities(
    val cameraId: String,
    val sensorOrientation: Int?,
    val availableLenses: List<LensRecord>,
    val fhd: Boolean,
    val uhd: Boolean,
    val fps60: Boolean,
    val flash: Boolean
) {
    data class LensRecord(
        val id: String,
        val facing: Int?,
        val approximateHorizontalFovDegrees: Double?,
        val physicalIds: Set<String>
    )

    fun hasQuality(quality: Quality): Boolean =
        when (quality) {
            Quality.UHD -> uhd
            Quality.FHD -> fhd
            else -> false
        }

    companion object {
        fun inspect(context: Context, info: CameraInfo): CameraCapabilities {
            val camera2 = Camera2CameraInfo.from(info)
            val id = camera2.cameraId
            val supported = Recorder.getVideoCapabilities(info)
                .getSupportedQualities(DynamicRange.SDR)
            val ranges = camera2.getCameraCharacteristic(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            )
            val fps60 = ranges?.any { it.lower <= 60 && it.upper >= 60 } == true
            val flash = info.hasFlashUnit()

            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val lenses = manager.cameraIdList.mapNotNull { lensId ->
                runCatching {
                    val characteristics = manager.getCameraCharacteristics(lensId)
                    val physical = characteristics.physicalCameraIds
                    val f = characteristics.get(
                        CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                    )?.firstOrNull()?.toDouble()
                    val sensor = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val fov = if (f != null && f > 0 && sensor != null)
                        Math.toDegrees(2.0 * kotlin.math.atan(sensor.width / (2.0 * f)))
                    else null
                    LensRecord(
                        lensId,
                        characteristics.get(CameraCharacteristics.LENS_FACING),
                        fov,
                        physical
                    )
                }.getOrNull()
            }

            return CameraCapabilities(
                id,
                camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION),
                lenses,
                supported.contains(Quality.FHD),
                supported.contains(Quality.UHD),
                fps60,
                flash
            )
        }
    }
}
