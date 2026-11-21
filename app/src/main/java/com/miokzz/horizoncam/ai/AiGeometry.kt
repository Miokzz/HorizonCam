package com.miokzz.horizoncam.ai

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Fixed pretrained model geometry; changing tile shape would invalidate its contract. */
object AiGeometry {
    const val TILE = 50
    const val SCALE = 4
    const val HALO = 9
    const val CORE = TILE - 2 * HALO
    const val STRIDE = 24

    data class Tile(val x: Int, val y: Int, val width: Int, val height: Int)

    fun tiles(width: Int, height: Int): List<Tile> {
        require(width > 0 && height > 0)
        fun origins(size: Int): List<Int> {
            if (size <= CORE) return listOf(0)
            val values = mutableListOf(0)
            while (values.last() + CORE < size) values.add(min(values.last() + STRIDE, size - CORE))
            return values
        }
        return origins(height).flatMap { y ->
            origins(width).map { x -> Tile(x, y, min(CORE, width - x), min(CORE, height - y)) }
        }
    }

    /** Reflect-101 padding has no artificial flat/black border, including one-pixel images. */
    fun mirror(index: Int, size: Int): Int {
        require(size > 0)
        if (size == 1) return 0
        val period = 2 * (size - 1)
        val folded = ((index % period) + period) % period
        return if (folded < size) folded else period - folded
    }

    /** Positive feather weights allow normalized overlap without uncovered edge pixels. */
    fun weight(position: Int, extent: Int): Float =
        min(1f, min(position + 1f, extent - position.toFloat()) / (8 * SCALE))

    fun scaledSize(width: Int, height: Int, cap: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && cap in 64..768)
        val factor = min(1.0, cap.toDouble() / max(width, height))
        return Pair(max(1, (width * factor).roundToInt()), max(1, (height * factor).roundToInt()))
    }

    /**
     * Conservative estimate, not a hard native allocator limit. Includes a 96MiB runtime reserve.
     */
    fun estimatedPeakBytes(sourceBytes: Long, width: Int, height: Int, scaledCopy: Boolean): Long {
        val pixels = width.toLong() * height
        val output = pixels * SCALE * SCALE
        return 96L * 1024 * 1024 +
            sourceBytes +
            pixels * 4 +
            (if (scaledCopy) pixels * 4 else 0) +
            output * 8 +
            12L * 1024 * 1024
    }
}

data class SceneMetrics(val noiseEstimate: Float, val edgeFraction: Float, val meanLuma: Float)

object AiColor {
    fun red(pixel: Int) = (pixel ushr 16) and 255

    fun green(pixel: Int) = (pixel ushr 8) and 255

    fun blue(pixel: Int) = pixel and 255

    fun luma(pixel: Int): Float = 0.299f * red(pixel) + 0.587f * green(pixel) + 0.114f * blue(pixel)

    fun pack(r: Float, g: Float, b: Float): Int =
        (255 shl 24) or
            (r.roundToInt().coerceIn(0, 255) shl 16) or
            (g.roundToInt().coerceIn(0, 255) shl 8) or
            b.roundToInt().coerceIn(0, 255)

    /**
     * Robust flat-region Laplacian proxy; scene structure can bias it, so it is not calibrated ISO
     * noise.
     */
    fun scene(pixels: IntArray, width: Int, height: Int): SceneMetrics {
        val histogram = IntArray(256)
        var flatPixelCount = 0
        var edgePixelCount = 0
        var sampleCount = 0
        var lumaSum = 0.0
        val sampleStep = max(1, max(width, height) / 256)
        for (y in 1 until height - 1 step sampleStep) for (x in 1 until width - 1 step sampleStep) {
            val center = luma(pixels[y * width + x])
            val leftLuma = luma(pixels[y * width + x - 1])
            val rightLuma = luma(pixels[y * width + x + 1])
            val upperLuma = luma(pixels[(y - 1) * width + x])
            val lowerLuma = luma(pixels[(y + 1) * width + x])
            val gradient = abs(rightLuma - leftLuma) + abs(lowerLuma - upperLuma)
            if (gradient > 24) edgePixelCount++
            if (gradient < 16) {
                val laplacianMagnitude =
                    abs(4 * center - leftLuma - rightLuma - upperLuma - lowerLuma)
                        .roundToInt()
                        .coerceIn(0, 255)
                histogram[laplacianMagnitude]++
                flatPixelCount++
            }
            lumaSum += center
            sampleCount++
        }
        var median = 0
        var accumulated = 0
        if (flatPixelCount > 0)
            for (i in histogram.indices) {
                accumulated += histogram[i]
                if (accumulated * 2 >= flatPixelCount) {
                    median = i
                    break
                }
            }
        return SceneMetrics(
            (median / 3.016f).coerceAtMost(40f),
            if (sampleCount == 0) 0f else edgePixelCount.toFloat() / sampleCount,
            if (sampleCount == 0) 0f else (lumaSum / sampleCount).toFloat(),
        )
    }

    /** Keep most bicubic chroma; apply learned luminance conservatively on noisy/flat regions. */
    fun mix(
        base: Int,
        modelRed: Float,
        modelGreen: Float,
        modelBlue: Float,
        strength: Float,
        noise: Float,
        edge: Float,
        preserveChroma: Boolean,
    ): Int {
        val baseRed = red(base).toFloat()
        val baseGreen = green(base).toFloat()
        val baseBlue = blue(base).toFloat()
        val clippedRed = modelRed.coerceIn(0f, 255f)
        val clippedGreen = modelGreen.coerceIn(0f, 255f)
        val clippedBlue = modelBlue.coerceIn(0f, 255f)
        val deltaLuma =
            0.299f * (clippedRed - baseRed) +
                0.587f * (clippedGreen - baseGreen) +
                0.114f * (clippedBlue - baseBlue)
        val edgeDetail = (edge / 32f).coerceIn(0f, 1f)
        val flatSafety = 0.55f + 0.45f * edgeDetail
        val noiseSafety = 1f - 0.50f * (noise / 12f).coerceIn(0f, 1f) * (1f - edgeDetail)
        val blendAmount = strength * flatSafety * noiseSafety
        val chromaAmount = if (preserveChroma) 0.15f else 1f
        return pack(
            baseRed +
                blendAmount * (deltaLuma + chromaAmount * ((clippedRed - baseRed) - deltaLuma)),
            baseGreen +
                blendAmount * (deltaLuma + chromaAmount * ((clippedGreen - baseGreen) - deltaLuma)),
            baseBlue +
                blendAmount * (deltaLuma + chromaAmount * ((clippedBlue - baseBlue) - deltaLuma)),
        )
    }
}

/** Catmull-Rom bicubic baseline in global source coordinates; reused across all tiles. */
class AiBicubic(private val pixels: IntArray, private val width: Int, private val height: Int) {
    private data class Axis(val indices: IntArray, val weights: FloatArray)

    private fun cubic(value: Float): Float {
        val x = abs(value)
        return when {
            x <= 1f -> 1.5f * x * x * x - 2.5f * x * x + 1f
            x < 2f -> -0.5f * x * x * x + 2.5f * x * x - 4f * x + 2f
            else -> 0f
        }
    }

    private fun axis(size: Int) =
        Array(size * AiGeometry.SCALE) { out ->
            val center = (out + 0.5f) / AiGeometry.SCALE - 0.5f
            val anchor = kotlin.math.floor(center).toInt()
            Axis(
                IntArray(4) { AiGeometry.mirror(anchor + it - 1, size) },
                FloatArray(4) { cubic(center - (anchor + it - 1)) },
            )
        }

    private val horizontalSamples = axis(width)
    private val verticalSamples = axis(height)

    fun pixel(x: Int, y: Int): Int {
        val horizontalAxis = horizontalSamples[x]
        val verticalAxis = verticalSamples[y]
        var interpolatedRed = 0f
        var interpolatedGreen = 0f
        var interpolatedBlue = 0f
        for (j in 0..3) for (i in 0..3) {
            val pixel = pixels[verticalAxis.indices[j] * width + horizontalAxis.indices[i]]
            val weight = horizontalAxis.weights[i] * verticalAxis.weights[j]
            interpolatedRed += AiColor.red(pixel) * weight
            interpolatedGreen += AiColor.green(pixel) * weight
            interpolatedBlue += AiColor.blue(pixel) * weight
        }
        return AiColor.pack(interpolatedRed, interpolatedGreen, interpolatedBlue)
    }
}
