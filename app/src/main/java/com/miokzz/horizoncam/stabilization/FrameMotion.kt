package com.miokzz.horizoncam.stabilization

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

internal data class FrameShift(val x: Double, val y: Double) {
    operator fun times(factor: Double) = FrameShift(x * factor, y * factor)
    operator fun minus(other: FrameShift) = FrameShift(x - other.x, y - other.y)

    companion object { val ZERO = FrameShift(0.0, 0.0) }
}

/** Estimates image-plane shake between two already roll-corrected frames. */
internal class FrameMotion(private val width: Int, private val height: Int) {
    private var previous = ByteArray(width * height)
    private var current = ByteArray(width * height)
    val input: ByteArray get() = current
    private val patchCosts = IntArray((2 * SEARCH + 1) * (2 * SEARCH + 1))
    private val offsetsX = DoubleArray(COLUMNS * ROWS)
    private val offsetsY = DoubleArray(COLUMNS * ROWS)
    private val sortedX = DoubleArray(COLUMNS * ROWS)
    private val sortedY = DoubleArray(COLUMNS * ROWS)
    private var lastNs = 0L
    private var correction = FrameShift.ZERO

    var matchedPatches = 0
        private set

    init { require(width >= 48 && height >= 32) }

    fun reset() {
        lastNs = 0L
        correction = FrameShift.ZERO
        matchedPatches = 0
    }

    fun advance(timestampNs: Long): FrameShift {
        val gap = timestampNs - lastNs
        matchedPatches = 0
        if (lastNs == 0L || gap !in 1..150_000_000L) {
            correction = FrameShift.ZERO
        } else {
            val measured = estimate()
            val motion = if (measured == null) FrameShift.ZERO else
                FrameShift(2 * measured.x / width, 2 * measured.y / height)
            // Track a slowly moving camera path. Constant pans are followed while short
            // alternating motion is countered. An untracked frame returns gradually to center.
            val decay = exp(-gap / if (measured == null) 90_000_000.0 else 180_000_000.0)
            val proposed = (correction - motion) * decay
            val magnitude = maxOf(abs(proposed.x), abs(proposed.y))
            correction = if (magnitude > MAX_SHIFT) proposed * (MAX_SHIFT / magnitude) else proposed
        }
        val swap = previous
        previous = current
        current = swap
        lastNs = timestampNs
        return correction
    }

    private fun estimate(): FrameShift? {
        var used = 0
        for (row in 0 until ROWS) for (column in 0 until COLUMNS) {
            val x = width * (column + 1) / (COLUMNS + 1)
            val y = height * (row + 1) / (ROWS + 1)
            if (x < EDGE || y < EDGE || x >= width - EDGE || y >= height - EDGE) continue
            val shift = match(x, y) ?: continue
            offsetsX[used] = shift.x
            offsetsY[used] = shift.y
            sortedX[used] = shift.x
            sortedY[used] = shift.y
            used++
        }
        if (used < MIN_INLIERS) return null
        java.util.Arrays.sort(sortedX, 0, used)
        java.util.Arrays.sort(sortedY, 0, used)
        val medianX = sortedX[used / 2]
        val medianY = sortedY[used / 2]
        var sumX = 0.0
        var sumY = 0.0
        for (i in 0 until used) {
            if (abs(offsetsX[i] - medianX) > 1.15 || abs(offsetsY[i] - medianY) > 1.15) continue
            matchedPatches++
            sumX += offsetsX[i]
            sumY += offsetsY[i]
        }
        return if (matchedPatches >= MIN_INLIERS && matchedPatches * 5 >= used * 3)
            FrameShift(sumX / matchedPatches, sumY / matchedPatches) else null
    }

    private fun match(x: Int, y: Int): FrameShift? {
        var sum = 0
        var square = 0
        for (py in -PATCH..PATCH) for (px in -PATCH..PATCH) {
            val gray = previous[(y + py) * width + x + px].toInt() and 255
            sum += gray
            square += gray * gray
        }
        val texture = square * AREA - sum * sum
        if (texture < 32 * AREA * AREA) return null

        var best = Int.MAX_VALUE
        var bestX = 0
        var bestY = 0
        for (dy in -SEARCH..SEARCH) for (dx in -SEARCH..SEARCH) {
            var deltaSum = 0
            var deltaSquare = 0
            val step = dx + dy * width
            for (py in -PATCH..PATCH) {
                val rowStart = (y + py) * width + x
                for (px in -PATCH..PATCH) {
                    val i = rowStart + px
                    val delta = (current[i + step].toInt() and 255) - (previous[i].toInt() and 255)
                    deltaSum += delta
                    deltaSquare += delta * delta
                }
            }
            // Remove the mean offset to tolerate changes in exposure.
            val cost = deltaSquare * AREA - deltaSum * deltaSum
            patchCosts[index(dx, dy)] = cost
            if (cost < best) {
                best = cost
                bestX = dx
                bestY = dy
            }
        }
        if (abs(bestX) == SEARCH || abs(bestY) == SEARCH || best > texture * 0.48) return null
        var alternative = Int.MAX_VALUE
        for (dy in -SEARCH..SEARCH) for (dx in -SEARCH..SEARCH) {
            if (abs(dx - bestX) > 1 || abs(dy - bestY) > 1)
                alternative = minOf(alternative, patchCosts[index(dx, dy)])
        }
        if (alternative - best < maxOf(4 * AREA * AREA, best / 4)) return null
        val refineX = parabola(patchCosts[index(bestX - 1, bestY)], best, patchCosts[index(bestX + 1, bestY)])
        val refineY = parabola(patchCosts[index(bestX, bestY - 1)], best, patchCosts[index(bestX, bestY + 1)])
        return FrameShift(bestX + refineX, bestY + refineY)
    }

    private fun index(x: Int, y: Int) = (y + SEARCH) * (2 * SEARCH + 1) + x + SEARCH

    private fun parabola(left: Int, center: Int, right: Int): Double {
        val curvature = left.toDouble() + right - 2 * center
        return if (curvature > 0) ((left.toDouble() - right) / (2 * curvature)).coerceIn(-0.5, 0.5) else 0.0
    }

    companion object {
        private const val PATCH = 3
        private const val SEARCH = 4
        private const val AREA = (2 * PATCH + 1) * (2 * PATCH + 1)
        private const val EDGE = PATCH + SEARCH
        private const val COLUMNS = 5
        private const val ROWS = 3
        private const val MIN_INLIERS = 4
        private const val MAX_SHIFT = 0.12
    }
}

/** A single shared correction is attenuated to fit every frame without black borders. */
internal fun safeShiftFactor(degrees: Double, zoom: Double, aspect: Double, shift: FrameShift): Double {
    val maxAxis = maxOf(abs(shift.x), abs(shift.y))
    if (maxAxis == 0.0) return 1.0
    val theta = Math.toRadians(degrees)
    val xExtent = abs(cos(theta)) + abs(sin(theta)) / aspect
    val yExtent = abs(cos(theta)) + abs(sin(theta)) * aspect
    val room = zoom / maxOf(xExtent, yExtent) - 1.005
    return (room / maxAxis).coerceIn(0.0, 1.0)
}

internal data class MotionViewport(val width: Int, val height: Int, val sensorToBuffer: AngleMath.Matrix2, val rollCrop: Double) {
    fun shiftFrom(reference: MotionViewport, shift: FrameShift): FrameShift {
        if (reference === this || shift == FrameShift.ZERO) return shift
        val sensor = reference.sensorToBuffer.inverse().apply(
            shift.x * reference.width / reference.rollCrop, shift.y * reference.height / reference.rollCrop)
        val target = sensorToBuffer.apply(sensor.first, sensor.second)
        return FrameShift(target.first * rollCrop / width, target.second * rollCrop / height)
    }
}
