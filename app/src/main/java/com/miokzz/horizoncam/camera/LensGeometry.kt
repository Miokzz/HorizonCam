package com.miokzz.horizoncam.camera

import kotlin.math.tan

object LensGeometry {
    /** Rectilinear scale estimate; distortion, stream crop and calibration can alter actual framing. */
    fun relativeZoom(primaryHorizontalFov: Double?, otherHorizontalFov: Double?): Float? {
        val primary = primaryHorizontalFov?.takeIf { it.isFinite() && it > 0 && it < 180 } ?: return null
        val other = otherHorizontalFov?.takeIf { it.isFinite() && it > 0 && it < 180 } ?: return null
        return (tan(Math.toRadians(primary / 2)) / tan(Math.toRadians(other / 2))).toFloat()
    }
}
