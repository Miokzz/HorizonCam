package com.miokzz.horizoncam.photo

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageCapture

data class AdvancedPhotoCapabilities(
    val formats: Set<PhotoFormat> = setOf(PhotoFormat.JPEG),
    val normalJpegSizes: List<PhotoSize> = emptyList(),
    val highResolutionJpegSizes: List<PhotoSize> = emptyList(),
    val rawSizes: List<PhotoSize> = emptyList(),
    val manual: ManualPhotoCapabilities = ManualPhotoCapabilities()
) {
    val highResolutionAvailable: Boolean get() = largerHighResolutionSizes(normalJpegSizes, highResolutionJpegSizes).isNotEmpty()
    val rawAvailable: Boolean get() = PhotoFormat.RAW_DNG in formats && rawSizes.isNotEmpty()

    fun validate(settings: AdvancedPhotoSettings, extensionActive: Boolean = false) {
        require(settings.format in formats) { "Formato de foto indisponível nesta câmera." }
        require(settings.format != PhotoFormat.RAW_DNG || rawAvailable) { "RAW DNG indisponível nesta câmera." }
        require(!settings.highResolution || (settings.format == PhotoFormat.JPEG && highResolutionAvailable)) {
            "Alta resolução JPEG indisponível nesta câmera."
        }
        require(!extensionActive || (settings.format == PhotoFormat.JPEG && !settings.highResolution)) {
            "HDR requer JPEG em resolução padrão."
        }
        settings.manual.validate(manual, extensionActive)
    }

    companion object {
        /** Only additional standard-sensor high-resolution sizes; never maximum-resolution pixel mode. */
        fun largerHighResolutionSizes(normal: List<PhotoSize>, high: List<PhotoSize>): List<PhotoSize> {
            val maximumNormal = normal.maxOfOrNull { it.pixels } ?: return emptyList()
            return high.filter { it.width > 0 && it.height > 0 && it.pixels > maximumNormal }
                .distinct().sortedByDescending { it.pixels }
        }

        @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
        fun inspect(context: Context, cameraInfo: CameraInfo, physicalCameraId: String? = null,
                    extensionActive: Boolean = false): AdvancedPhotoCapabilities {
            val camera2 = Camera2CameraInfo.from(cameraInfo)
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = manager.getCameraCharacteristics(physicalCameraId ?: camera2.cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            fun sizes(format: Int, high: Boolean = false): List<PhotoSize> = runCatching {
                val values = if (high) map?.getHighResolutionOutputSizes(format) else map?.getOutputSizes(format)
                values?.map { PhotoSize(it.width,it.height) }?.distinct()?.sortedByDescending { it.pixels }.orEmpty()
            }.getOrDefault(emptyList())
            val jpeg = sizes(ImageFormat.JPEG)
            val high = if (extensionActive) emptyList() else sizes(ImageFormat.JPEG, true)
            val raw = if (extensionActive) emptyList() else sizes(ImageFormat.RAW_SENSOR)
            val formats = mutableSetOf(PhotoFormat.JPEG)
            val cameraXFormats = ImageCapture.getImageCaptureCapabilities(cameraInfo).supportedOutputFormats
            if (!extensionActive && ImageCapture.OUTPUT_FORMAT_RAW in cameraXFormats && raw.isNotEmpty()) {
                formats.add(PhotoFormat.RAW_DNG)
            }
            val keys = characteristics.availableCaptureRequestKeys?.map { it.name }?.toSet().orEmpty()
            val iso = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposure = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val focus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            val allowedAwb = setOf(CaptureRequest.CONTROL_AWB_MODE_AUTO, CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
                CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT, CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT,
                CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
                CaptureRequest.CONTROL_AWB_MODE_TWILIGHT, CaptureRequest.CONTROL_AWB_MODE_SHADE)
            val awb = if (ManualPhotoKeys.AWB_MODE in keys) characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                ?.filter { it in allowedAwb }.orEmpty() else emptyList()
            val maximumFrame = characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)
            // Interop writes target the logical control. A physical stream alone does not guarantee manual control of that sensor.
            val manual = if (extensionActive || physicalCameraId != null) ManualPhotoCapabilities() else ManualPhotoCapabilities(
                isoRange = iso?.let { it.lower..it.upper },
                exposureTimeNsRange = exposure?.let { it.lower..minOf(it.upper, maximumFrame ?: it.upper) },
                maxFrameDurationNs = maximumFrame,
                focusDistanceRange = focus?.takeIf { it.isFinite() && it > 0f }?.let { 0f..it },
                awbModes = awb,
                requestKeys = keys,
                manualSensor = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true,
                aeOffAvailable = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
                    ?.contains(CaptureRequest.CONTROL_AE_MODE_OFF) == true,
                afOffAvailable = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                    ?.contains(CaptureRequest.CONTROL_AF_MODE_OFF) == true
            )
            return AdvancedPhotoCapabilities(formats,jpeg,high,raw,manual)
        }
    }
}
