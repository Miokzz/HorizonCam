package com.miokzz.horizoncam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.DynamicRange
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import com.miokzz.horizoncam.video.VideoFpsRange
import com.miokzz.horizoncam.video.VideoRatePolicy

@androidx.annotation.OptIn(
    markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class]
)
data class CameraCapabilities(
    val cameraId: String,
    val sensorOrientation: Int?,
    val availableLenses: List<LensRecord>,
    val fhd: Boolean,
    val uhd: Boolean,
    val fps60: Boolean,
    val uhd60: Boolean,
    val flash: Boolean,
    val videoFrameRateRanges: Map<Quality, List<VideoFpsRange>> = emptyMap(),
    val nativeEisDeclared: Boolean = false,
    val videoQualities: Set<Quality> = emptySet(),
) {
    data class LensRecord(
        val id: String,
        val facing: Int?,
        val approximateHorizontalFovDegrees: Double?,
        val physicalIds: Set<String>,
    )

    fun hasQuality(quality: Quality): Boolean =
        when (quality) {
            Quality.UHD -> uhd
            Quality.FHD -> fhd
            else -> quality in videoQualities
        }

    /** Declared quality-specific rates; no requested/default cadence is called verified here. */
    fun verifiedFpsFor(quality: Quality): Set<Int> =
        VideoRatePolicy.frameRates(videoFrameRateRanges[quality].orEmpty())

    /** Nominal 30 remains available as an unverified regular-session fallback. */
    fun fpsFor(quality: Quality): Set<Int> =
        VideoRatePolicy.frameRates(videoFrameRateRanges[quality].orEmpty()) +
            (if (
                hasQuality(quality) ||
                    (quality == Quality.FHD &&
                        videoQualities.any { it == Quality.HD || it == Quality.SD })
            )
                setOf(30)
            else emptySet())

    fun supportsFps(quality: Quality, fps: Int): Boolean = fps in fpsFor(quality)

    companion object {
        fun inspect(context: Context, info: CameraInfo): CameraCapabilities {
            val camera2 = Camera2CameraInfo.from(info)
            val id = camera2.cameraId
            val supported =
                Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
            val flash = info.hasFlashUnit()
            val videoRates =
                listOf(Quality.FHD, Quality.UHD, Quality.HD, Quality.SD)
                    .filter { it in supported }
                    .associateWith { quality ->
                        runCatching {
                                val preview =
                                    Preview.Builder().setDynamicRange(DynamicRange.SDR).build()
                                val recorder =
                                    Recorder.Builder()
                                        .setQualitySelector(QualitySelector.from(quality))
                                        .build()
                                val capture =
                                    VideoCapture.Builder(recorder)
                                        .setDynamicRange(DynamicRange.SDR)
                                        .build()
                                val session = SessionConfig.Builder(preview, capture).build()
                                info
                                    .getSupportedFrameRateRanges(session)
                                    .map { VideoFpsRange(it.lower, it.upper) }
                                    .sortedWith(
                                        compareBy<VideoFpsRange> { it.upper }.thenBy { it.lower }
                                    )
                            }
                            .getOrDefault(emptyList())
                    }
            // A global AE60 declaration does not establish UHD60 capability.
            val fps60 = 60 in VideoRatePolicy.frameRates(videoRates[Quality.FHD].orEmpty())
            val uhd60 = 60 in VideoRatePolicy.frameRates(videoRates[Quality.UHD].orEmpty())
            val eis =
                Preview.getPreviewCapabilities(info).isStabilizationSupported &&
                    Recorder.getVideoCapabilities(info).isStabilizationSupported

            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val lenses =
                manager.cameraIdList.mapNotNull { lensId ->
                    runCatching {
                            val characteristics = manager.getCameraCharacteristics(lensId)
                            val physical = characteristics.physicalCameraIds
                            val f =
                                characteristics
                                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                                    ?.firstOrNull()
                                    ?.toDouble()
                            val sensor =
                                characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                            val fov =
                                if (f != null && f > 0 && sensor != null)
                                    Math.toDegrees(
                                        2.0 * kotlin.math.atan(sensor.width.toDouble() / (2.0 * f))
                                    )
                                else null
                            LensRecord(
                                lensId,
                                characteristics.get(CameraCharacteristics.LENS_FACING),
                                fov,
                                physical,
                            )
                        }
                        .getOrNull()
                }

            return CameraCapabilities(
                id,
                camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION),
                lenses,
                supported.contains(Quality.FHD),
                supported.contains(Quality.UHD),
                fps60,
                uhd60,
                flash,
                videoRates,
                eis,
                supported.toSet(),
            )
        }
    }
}
