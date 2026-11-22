package com.miokzz.horizoncam.camera

import kotlin.math.ln
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/** Equal slider distances multiply the field of view by the same factor. */
internal object PhotoZoomScale {
    const val STEPS = 3000

    fun zoom(progress: Int, minimum: Float): Float {
        val position = progress.coerceIn(0, STEPS).toFloat() / STEPS
        return minimum * (StillFraming.MAX_ZOOM / minimum).pow(position)
    }

    fun progress(zoom: Float, minimum: Float): Int {
        val position = ln(zoom.coerceIn(minimum, StillFraming.MAX_ZOOM) / minimum) /
            ln(StillFraming.MAX_ZOOM / minimum)
        return (position * STEPS).roundToInt()
    }

    fun tenths(zoom: Float): Float = floor(zoom * 10f + 0.0001f) / 10f
}

/** A detent helps precise stops without forcing fast drags to visit every integer. */
internal class PhotoZoomDetents {
    private var previous = 1f
    private var held: Int? = null
    private var until = 0L

    fun start(zoom: Float) {
        previous = zoom
        held = null
    }

    fun update(rawZoom: Float, now: Long): Float {
        val before = previous
        previous = rawZoom
        held?.let { integer ->
            if (now < until && abs(rawZoom - integer) <= 0.35f) return integer.toFloat()
            held = null
            return PhotoZoomScale.tenths(rawZoom)
        }
        val integer = if (rawZoom > before) floor(rawZoom).toInt() else ceil(rawZoom).toInt()
        val crossed = (before < integer && rawZoom >= integer) || (before > integer && rawZoom <= integer)
        if (integer in 1..29 && crossed && abs(rawZoom - integer) <= 0.15f) {
            held = integer
            until = now + 500L
            return integer.toFloat()
        }
        return PhotoZoomScale.tenths(rawZoom)
    }
}
