package com.miokzz.horizoncam.photo.fusion

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object FusionMath {
    fun red(pixel: Int) = (pixel ushr 16) and 255

    fun green(pixel: Int) = (pixel ushr 8) and 255

    fun blue(pixel: Int) = pixel and 255

    fun luma(pixel: Int) = 0.299f * red(pixel) + 0.587f * green(pixel) + 0.114f * blue(pixel)

    fun pack(red: Double, green: Double, blue: Double) =
        (255 shl 24) or
            (red.roundToInt().coerceIn(0, 255) shl 16) or
            (green.roundToInt().coerceIn(0, 255) shl 8) or
            blue.roundToInt().coerceIn(0, 255)

    fun gray(frame: FusionFrame) = FloatArray(frame.pixels.size) { luma(frame.pixels[it]) }

    fun sample(gray: FloatArray, width: Int, height: Int, x: Double, y: Double): Double {
        val xx = x.coerceIn(0.0, width - 1.0)
        val yy = y.coerceIn(0.0, height - 1.0)
        val left = floor(xx).toInt()
        val top = floor(yy).toInt()
        val right = (left + 1).coerceAtMost(width - 1)
        val bottom = (top + 1).coerceAtMost(height - 1)
        val fx = xx - left
        val fy = yy - top
        return (gray[top * width + left] * (1 - fx) + gray[top * width + right] * fx) * (1 - fy) +
            (gray[bottom * width + left] * (1 - fx) + gray[bottom * width + right] * fx) * fy
    }

    fun channel(frame: FusionFrame, x: Double, y: Double, channel: Int): Double {
        val xx = x.coerceIn(0.0, frame.width - 1.0)
        val yy = y.coerceIn(0.0, frame.height - 1.0)
        val left = floor(xx).toInt()
        val top = floor(yy).toInt()
        val right = (left + 1).coerceAtMost(frame.width - 1)
        val bottom = (top + 1).coerceAtMost(frame.height - 1)
        val fx = xx - left
        val fy = yy - top
        fun value(index: Int) = ((frame.pixels[index] ushr channel) and 255).toDouble()
        return (value(top * frame.width + left) * (1 - fx) +
            value(top * frame.width + right) * fx) * (1 - fy) +
            (value(bottom * frame.width + left) * (1 - fx) +
                value(bottom * frame.width + right) * fx) * fy
    }

    fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        values.sort()
        return if (values.size % 2 == 1) values[values.size / 2]
        else (values[values.size / 2 - 1] + values[values.size / 2]) / 2
    }

    fun noise(gray: FloatArray, width: Int, height: Int): Double {
        val histogram = IntArray(256)
        var count = 0
        val step = max(1, max(width, height) / 192)
        for (y in 1 until height - 1 step step) for (x in 1 until width - 1 step step) {
            val center = gray[y * width + x]
            val left = gray[y * width + x - 1]
            val right = gray[y * width + x + 1]
            val up = gray[(y - 1) * width + x]
            val down = gray[(y + 1) * width + x]
            if (abs(right - left) + abs(down - up) < 20) {
                histogram[
                    abs(4 * center - left - right - up - down).roundToInt().coerceIn(0, 255)]++
                count++
            }
        }
        if (count == 0) return 2.0
        var sum = 0
        for (i in histogram.indices) {
            sum += histogram[i]
            if (sum * 2 >= count) return (i / 3.016).coerceIn(0.5, 30.0)
        }
        return 2.0
    }

    /** A lightly smoothed gradient rejects blur without rewarding high-frequency sensor noise. */
    fun sharpness(gray: FloatArray, width: Int, height: Int): Double {
        var sum = 0.0
        var count = 0
        val step = max(1, max(width, height) / 192)
        for (y in 2 until height - 2 step step) for (x in 2 until width - 2 step step) {
            val gx =
                (gray[(y - 1) * width + x + 1] +
                    2 * gray[y * width + x + 1] +
                    gray[(y + 1) * width + x + 1]) -
                    (gray[(y - 1) * width + x - 1] +
                        2 * gray[y * width + x - 1] +
                        gray[(y + 1) * width + x - 1])
            val gy =
                (gray[(y + 1) * width + x - 1] +
                    2 * gray[(y + 1) * width + x] +
                    gray[(y + 1) * width + x + 1]) -
                    (gray[(y - 1) * width + x - 1] +
                        2 * gray[(y - 1) * width + x] +
                        gray[(y - 1) * width + x + 1])
            sum += (gx * gx + gy * gy) / 64.0
            count++
        }
        return if (count == 0) 0.0 else sum / count
    }

    fun grayVariance(gray: FloatArray): Double {
        val mean = gray.average()
        return gray.sumOf { (it - mean) * (it - mean) } / gray.size
    }

    fun exposure(frame: FusionFrame): Double? {
        val time = frame.exposureTimeNs?.takeIf { it > 0 } ?: return null
        val iso = frame.iso?.takeIf { it > 0 } ?: return null
        return time.toDouble() * iso
    }

    fun motionThreshold(referenceNoise: Double, candidateNoise: Double) =
        max(12.0, 3.5 * sqrt(referenceNoise * referenceNoise + candidateNoise * candidateNoise))
            .coerceAtMost(48.0)
}
