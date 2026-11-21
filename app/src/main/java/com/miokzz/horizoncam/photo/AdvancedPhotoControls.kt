package com.miokzz.horizoncam.photo

import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import com.google.common.util.concurrent.ListenableFuture

/** Public Camera2 overrides affect preview AND still requests. Await success before capture. */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
object AdvancedPhotoControls {
    fun apply(camera: Camera, settings: ManualPhotoSettings, capabilities: AdvancedPhotoCapabilities,
              extensionActive: Boolean = false): ListenableFuture<Void?> {
        settings.validate(capabilities.manual, extensionActive)
        val options = CaptureRequestOptions.Builder()
        if (settings.iso != null && settings.exposureTimeNs != null) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, settings.iso)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.exposureTimeNs)
            // Explicit frame duration lets a long shutter exceed the normal
            // preview cadence without relying on a stale 30fps default.
            val duration = maxOf(settings.exposureTimeNs, 33_333_333L)
                .coerceAtMost(capabilities.manual.maxFrameDurationNs!!)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, duration)
        }
        settings.focusDistanceDiopters?.let {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        }
        settings.awbMode?.let { options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, it) }
        // Replacement is intentional: returning to Auto removes stale AE/AF/AWB
        // overrides instead of leaving the previous manual request merged in.
        return Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(options.build())
    }
}
