package com.miokzz.horizoncam.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.util.Size

/** The physical sensor's outputs. CameraX negotiates the logical device side of the intersection. */
data class PhysicalPhotoOutputs(
    val preview: Set<Size>,
    val jpeg: Set<Size>,
    val highResolutionJpeg: Set<Size>,
    val raw: Set<Size>
) {
    companion object {
        internal fun from(characteristics: CameraCharacteristics): PhysicalPhotoOutputs? {
            val streams = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
            return PhysicalPhotoOutputs(
                (streams.getOutputSizes(SurfaceTexture::class.java) ?: streams.getOutputSizes(ImageFormat.PRIVATE))?.toSet().orEmpty(),
                streams.getOutputSizes(ImageFormat.JPEG)?.toSet().orEmpty(),
                streams.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toSet().orEmpty(),
                streams.getOutputSizes(ImageFormat.RAW_SENSOR)?.toSet().orEmpty()
            )
        }
    }
}
