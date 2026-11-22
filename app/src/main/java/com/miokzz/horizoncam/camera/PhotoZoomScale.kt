package com.miokzz.horizoncam.camera

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Equal slider distances multiply the field of view by the same factor. */
internal object PhotoZoomScale {
    const val STEPS = 1000

    fun zoom(progress: Int, minimum: Float): Float {
        val position = progress.coerceIn(0, STEPS).toFloat() / STEPS
        return minimum * (StillFraming.MAX_ZOOM / minimum).pow(position)
    }

    fun progress(zoom: Float, minimum: Float): Int {
        val position = ln(zoom.coerceIn(minimum, StillFraming.MAX_ZOOM) / minimum) /
            ln(StillFraming.MAX_ZOOM / minimum)
        return (position * STEPS).roundToInt()
    }
}
