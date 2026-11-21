package com.miokzz.horizoncam.media

import kotlin.math.roundToInt

/** Map a point selected on an EXIF-upright preview back to the encoded pixel grid. */
object PhotoRegion {
    fun toEncoded(x: Float, y: Float, orientation: Int): Pair<Float, Float> {
        val u = x.coerceIn(0f, 1f)
        val v = y.coerceIn(0f, 1f)
        return when (orientation) {
            2 -> 1 - u to v
            3 -> 1 - u to 1 - v
            4 -> u to 1 - v
            5 -> v to u
            6 -> v to 1 - u
            7 -> 1 - v to 1 - u
            8 -> 1 - v to u
            else -> u to v
        }
    }

    data class Patch(val left: Int, val top: Int, val size: Int)
    fun patch(width: Int, height: Int, edge: Int, x: Float, y: Float, orientation: Int): Patch {
        require(width > 0 && height > 0 && edge > 0)
        val size = minOf(width, height, edge)
        val (u, v) = toEncoded(x, y, orientation)
        return Patch((u * width - size / 2f).roundToInt().coerceIn(0, width - size),
            (v * height - size / 2f).roundToInt().coerceIn(0, height - size), size)
    }
}
