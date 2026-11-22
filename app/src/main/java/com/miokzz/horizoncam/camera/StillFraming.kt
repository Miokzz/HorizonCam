package com.miokzz.horizoncam.camera

import kotlin.math.roundToInt

/** Coordinates are in the upright CameraX JPEG or matching 4:3 preview viewport. */
object StillFraming {
    const val MAX_ZOOM = 30f
    data class Crop(val left: Int, val top: Int, val width: Int, val height: Int)

    fun digitalRatio(requested: Float, supplied: Float): Float {
        require(requested.isFinite() && supplied.isFinite() && supplied > 0f)
        return (requested.coerceAtMost(MAX_ZOOM) / supplied).coerceAtLeast(1f)
    }

    fun reconstructionScale(width: Int, height: Int, residual: Float, maxPixels: Int): Int {
        val area = width.toLong() * height
        return listOf(4, 2, 1).first { it == 1 || it <= residual && area * it * it <= maxPixels }
    }

    fun centerCrop(width: Int, height: Int, ratio: Float): Crop {
        require(width > 0 && height > 0 && ratio.isFinite() && ratio >= 1f)
        val w = (width / ratio).roundToInt().coerceIn(1, width)
        val h = (height / ratio).roundToInt().coerceIn(1, height)
        return Crop((width - w) / 2, (height - h) / 2, w, h)
    }
}
