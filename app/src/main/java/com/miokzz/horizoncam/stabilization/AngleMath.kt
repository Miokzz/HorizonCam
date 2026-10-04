package com.miokzz.horizoncam.stabilization

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

object AngleMath {
    fun wrap(degrees: Double): Double {
        if (!degrees.isFinite()) return 0.0
        var value = (degrees + 180.0) % 360.0
        if (value < 0) value += 360.0
        return value - 180.0
    }

    fun delta(from: Double, to: Double): Double = wrap(to - from)

    fun gravityRoll(x: Double, y: Double): Double =
        Math.toDegrees(atan2(x, -y))

    fun safeFixedCrop(aspect: Double, safety: Double = 1.015): Double {
        require(aspect.isFinite() && aspect > 0.0)
        // The maximum of |cos(t)| + a*|sin(t)| is sqrt(1+a²).
        val a = max(aspect, 1.0 / aspect)
        return sqrt(1.0 + a*a) * safety
    }

    data class Matrix2(
        val m00: Double, val m01: Double,
        val m10: Double, val m11: Double
    ) {
        fun apply(x: Double, y: Double) =
            Pair(m00 * x + m01 * y, m10 * x + m11 * y)
        fun inverse(): Matrix2 {
            val det = m00*m11 - m01*m10
            require(abs(det) > 1e-12)
            return Matrix2(m11/det, -m01/det, -m10/det, m00/det)
        }
    }

    /** GL vertex matrix for a rotation in a rectangular, pixel-correct frame. */
    fun correctionMatrix(degrees: Double, zoom: Double, aspect: Double): Matrix2 {
        val r = Math.toRadians(degrees)
        val c = cos(r)
        val s = sin(r)
        return Matrix2(zoom*c, -zoom*s/aspect, zoom*s*aspect, zoom*c)
    }
}
