package com.miokzz.horizoncam.photo

enum class PhotoFormat { JPEG, RAW_DNG }

data class PhotoOutputSpec(val extension: String, val mimeType: String, val relativePath: String) {
    companion object {
        fun forFormat(format: PhotoFormat) = when (format) {
            PhotoFormat.JPEG -> PhotoOutputSpec("jpg", "image/jpeg", "Pictures/HorizonCam")
            PhotoFormat.RAW_DNG -> PhotoOutputSpec("dng", "image/x-adobe-dng", "Pictures/HorizonCam")
        }
    }
}

data class PhotoSize(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}

/** Public request-key names, kept Android-free for capability and range regressions. */
object ManualPhotoKeys {
    const val AE_MODE = "android.control.aeMode"
    const val ISO = "android.sensor.sensitivity"
    const val EXPOSURE = "android.sensor.exposureTime"
    const val FRAME_DURATION = "android.sensor.frameDuration"
    const val AF_MODE = "android.control.afMode"
    const val FOCUS = "android.lens.focusDistance"
    const val AWB_MODE = "android.control.awbMode"
}

data class ManualPhotoCapabilities(
    val isoRange: IntRange? = null,
    val exposureTimeNsRange: LongRange? = null,
    val maxFrameDurationNs: Long? = null,
    val focusDistanceRange: ClosedFloatingPointRange<Float>? = null,
    val awbModes: List<Int> = emptyList(),
    val requestKeys: Set<String> = emptySet(),
    val manualSensor: Boolean = false,
    val aeOffAvailable: Boolean = false,
    val afOffAvailable: Boolean = false
) {
    val supportsManualExposure: Boolean get() = manualSensor && aeOffAvailable &&
        isoRange?.let { !it.isEmpty() && it.first > 0 } == true &&
        exposureTimeNsRange?.let { !it.isEmpty() && it.first > 0 } == true &&
        maxFrameDurationNs?.let { it > 0L } == true &&
        requestKeys.containsAll(setOf(ManualPhotoKeys.AE_MODE, ManualPhotoKeys.ISO, ManualPhotoKeys.EXPOSURE, ManualPhotoKeys.FRAME_DURATION))
    val supportsManualFocus: Boolean get() = afOffAvailable && focusDistanceRange?.let {
        it.start.isFinite() && it.endInclusive.isFinite() && it.start >= 0f && it.endInclusive > it.start
    } == true && requestKeys.containsAll(setOf(ManualPhotoKeys.AF_MODE, ManualPhotoKeys.FOCUS))
}

data class ManualPhotoSettings(
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val focusDistanceDiopters: Float? = null,
    val awbMode: Int? = null
) {
    val isAuto: Boolean get() = iso == null && exposureTimeNs == null && focusDistanceDiopters == null && awbMode == null

    fun validate(capabilities: ManualPhotoCapabilities, extensionActive: Boolean = false): ManualPhotoSettings {
        require(!extensionActive || isAuto) { "A extensão HDR requer controles automáticos." }
        require((iso == null) == (exposureTimeNs == null)) { "ISO e tempo de exposição devem ser definidos juntos." }
        if (iso != null && exposureTimeNs != null) {
            require(capabilities.supportsManualExposure) { "Exposição manual indisponível nesta câmera." }
            require(iso in capabilities.isoRange!!) { "ISO fora da faixa suportada." }
            require(exposureTimeNs in capabilities.exposureTimeNsRange!!) { "Tempo de exposição fora da faixa suportada." }
            require(capabilities.maxFrameDurationNs == null || exposureTimeNs <= capabilities.maxFrameDurationNs) {
                "Tempo de exposição excede a duração máxima do sensor."
            }
        }
        focusDistanceDiopters?.let { value ->
            require(capabilities.supportsManualFocus) { "Foco manual indisponível nesta câmera." }
            require(value.isFinite() && value in capabilities.focusDistanceRange!!) { "Distância de foco fora da faixa suportada." }
        }
        awbMode?.let { mode ->
            // AWB_OFF needs explicit gains/transform; it is deliberately not offered.
            require(mode != 0 && mode in capabilities.awbModes && ManualPhotoKeys.AWB_MODE in capabilities.requestKeys) {
                "Balanço de branco indisponível nesta câmera."
            }
        }
        return this
    }
}

data class AdvancedPhotoSettings(
    val format: PhotoFormat = PhotoFormat.JPEG,
    val highResolution: Boolean = false,
    val manual: ManualPhotoSettings = ManualPhotoSettings()
)
