package com.miokzz.horizoncam.camera

import kotlin.math.tan
import kotlin.math.atan

object LensGeometry {
    /** Horizontal angle of the centered 4:3 window shared by the still streams. */
    fun stillHorizontalFov(focalMm: Double, widthMm: Double, heightMm: Double): Double? {
        if (!focalMm.isFinite() || !widthMm.isFinite() || !heightMm.isFinite() ||
            focalMm <= 0 || widthMm <= 0 || heightMm <= 0) return null
        val visibleWidth = minOf(widthMm, heightMm * 4 / 3)
        return Math.toDegrees(2 * atan(visibleWidth / (2 * focalMm)))
    }

    /** Rectilinear scale estimate; distortion, stream crop and calibration can alter actual framing. */
    fun relativeZoom(primaryHorizontalFov: Double?, otherHorizontalFov: Double?): Float? {
        val primary = primaryHorizontalFov?.takeIf { it.isFinite() && it > 0 && it < 180 } ?: return null
        val other = otherHorizontalFov?.takeIf { it.isFinite() && it > 0 && it < 180 } ?: return null
        return (tan(Math.toRadians(primary / 2)) / tan(Math.toRadians(other / 2))).toFloat()
            .takeIf { it.isFinite() && it > 0f }
    }
}
