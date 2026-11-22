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

    /** Android Y-down sensor pixels to buffer pixels, converted to GL Y-up. */
    fun sensorToBufferGlRotation(matrix: FloatArray): Float {
        require(matrix.size == 9)
        require(matrix[0].isFinite() && matrix[3].isFinite())
        return Math.toDegrees(atan2(-matrix[3].toDouble(), matrix[0].toDouble())).toFloat()
    }

    /** Column-major GLES matrix; the same pure pixel-metric math is regression tested. */
    fun correctionGlMatrix(degrees: Double, zoom: Double, aspect: Double): FloatArray {
        val m = correctionMatrix(degrees, zoom, aspect)
        return floatArrayOf(
            m.m00.toFloat(), m.m10.toFloat(), 0f, 0f,
            m.m01.toFloat(), m.m11.toFloat(), 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
    }

    /** GL vertex matrix for a rotation in a rectangular, pixel-correct frame. */
    fun correctionMatrix(degrees: Double, zoom: Double, aspect: Double): Matrix2 {
        require(aspect.isFinite() && aspect > 0.0 && zoom.isFinite() && zoom > 0.0)
        val r = Math.toRadians(degrees)
        val c = cos(r)
        val s = sin(r)
        return Matrix2(zoom*c, -zoom*s/aspect, zoom*s*aspect, zoom*c)
    }
}
