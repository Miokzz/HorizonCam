package com.miokzz.horizoncam.camera

import kotlin.math.abs

/** The request and result are from the same frame. Crop ratios are relative to the active array. */
data class NativeZoomObservation(
    val requestedControlRatio: Float?,
    val requestedCropRatio: Float?,
    val appliedControlRatio: Float?,
    val appliedCropRatio: Float?
) {
    fun appliedFor(commandRatio: Float): Float? {
        val control = requestedControlRatio.valid()
        val usesZoomControl = control != null && control != 1f
        val requested = if (usesZoomControl) control else requestedCropRatio.valid() ?: control
        if (requested == null || !commandRatio.isFinite() || commandRatio <= 0f ||
            abs(requested - commandRatio) > maxOf(0.002f, commandRatio * 0.005f)) return null
        // Camera2 reports crop-only zoom through SCALER_CROP_REGION. A 1.0 result for a non-1.0
        // CONTROL_ZOOM_RATIO request is meaningful and must never be replaced with the request.
        return if (usesZoomControl) appliedControlRatio.valid()
            else appliedCropRatio.valid() ?: appliedControlRatio.valid()
    }

    private fun Float?.valid(): Float? = this?.takeIf { it.isFinite() && it > 0f }
}
