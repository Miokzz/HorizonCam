package com.miokzz.horizoncam

import com.miokzz.horizoncam.ai.AiBicubic
import com.miokzz.horizoncam.ai.AiColor
import com.miokzz.horizoncam.ai.AiGeometry
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class AiGeometryTest {
    @Test
    fun tilesCoverEveryPixelWithoutDuplicateOriginsOrOutOfBoundsCores() {
        for ((width, height) in
            listOf(1 to 1, 17 to 93, 31 to 33, 64 to 96, 256 to 384, 640 to 480, 768 to 768)) {
            val tiles = AiGeometry.tiles(width, height)
            assertEquals(tiles.size, tiles.map { it.x to it.y }.distinct().size)
            val coverage = IntArray(width * height)
            for (tile in tiles) {
                assertTrue(
                    tile.x >= 0 &&
                        tile.y >= 0 &&
                        tile.x + tile.width <= width &&
                        tile.y + tile.height <= height
                )
                assertTrue(tile.width <= AiGeometry.CORE && tile.height <= AiGeometry.CORE)
                for (y in tile.y until tile.y + tile.height) for (x in
                    tile.x until tile.x + tile.width) coverage[y * width + x]++
            }
            assertTrue(coverage.all { it > 0 })
            if (width > AiGeometry.CORE && height > AiGeometry.CORE)
                assertTrue(coverage.any { it > 1 })
        }
    }

    @Test
    fun reflectedEdgesNeverInjectBlackOrRepeatAnInvalidIndex() {
        val expected = listOf(2, 1, 0, 1, 2, 3, 2, 1, 0, 1)
        assertEquals(expected, (-2..7).map { AiGeometry.mirror(it, 4) })
        for (size in listOf(1, 2, 31, 768)) for (index in -2000..2000) assertTrue(
            AiGeometry.mirror(index, size) in 0 until size
        )
        assertEquals(0, AiGeometry.mirror(Int.MIN_VALUE, 1))
    }

    @Test
    fun normalizedOverlapPreservesConstantImageIncludingEveryOutputEdge() {
        val width = 57
        val height = 41
        val outputWidth = width * 4
        val outputHeight = height * 4
        val sum = FloatArray(outputWidth * outputHeight)
        val weights = FloatArray(sum.size)
        for (tile in AiGeometry.tiles(width, height)) {
            val tw = tile.width * 4
            val th = tile.height * 4
            for (y in 0 until th) for (x in 0 until tw) {
                val weight = AiGeometry.weight(x, tw) * AiGeometry.weight(y, th)
                assertTrue(weight > 0f && weight <= 1f)
                val index = (tile.y * 4 + y) * outputWidth + tile.x * 4 + x
                weights[index] += weight
                sum[index] += 127f * weight
            }
        }
        for (i in sum.indices) {
            assertTrue(weights[i] > 0f)
            assertEquals(127f, sum[i] / weights[i], 0.0001f)
        }
    }

    @Test
    fun wholeFrameSizingIsExplicitBoundedAspectPreservingAndNeverUpsamplesInput() {
        assertEquals(640 to 480, AiGeometry.scaledSize(4000, 3000, 640))
        assertEquals(432 to 768, AiGeometry.scaledSize(1080, 1920, 768))
        assertEquals(256 to 384, AiGeometry.scaledSize(256, 384, 640))
        assertEquals(640 to 1, AiGeometry.scaledSize(10000, 1, 640))
        val square = AiGeometry.estimatedPeakBytes(768L * 768 * 4, 768, 768, false)
        assertTrue(square < 192L * 1024 * 1024)
        assertTrue(
            square > 768L * 768 * 16 * 8
        ) // Includes model/input/buffers, not just bitmap bytes.
        assertTrue(AiGeometry.estimatedPeakBytes(100_000_000L, 768, 768, true) > 192L * 1024 * 1024)
    }

    @Test
    fun bicubicPreservesFlatColorWithMirroredBordersEvenForOnePixel() {
        val color = AiColor.pack(70f, 130f, 210f)
        for ((width, height) in listOf(1 to 1, 2 to 9, 17 to 3)) {
            val sampler = AiBicubic(IntArray(width * height) { color }, width, height)
            for (y in 0 until height * 4) for (x in 0 until width * 4) assertEquals(
                color,
                sampler.pixel(x, y),
            )
        }
    }

    @Test
    fun zeroStrengthIsExactBaselineAndChromaPreservationLimitsColorShift() {
        val base = AiColor.pack(120f, 120f, 120f)
        assertEquals(base, AiColor.mix(base, 255f, 0f, 0f, 0f, 5f, 12f, true))
        val preserved = AiColor.mix(base, 240f, 20f, 70f, 0.65f, 0f, 40f, true)
        val fullColor = AiColor.mix(base, 240f, 20f, 70f, 0.65f, 0f, 40f, false)
        val preservedChroma = abs(AiColor.red(preserved) - AiColor.green(preserved))
        val fullChroma = abs(AiColor.red(fullColor) - AiColor.green(fullColor))
        assertTrue(preservedChroma < fullChroma * 0.2)
        assertEquals(AiColor.luma(fullColor), AiColor.luma(preserved), 1.0f)
    }

    @Test
    fun noiseProtectionReducesFlatAreaHallucinationAndSceneMetricsAreFinite() {
        val base = AiColor.pack(100f, 100f, 100f)
        val quiet = AiColor.mix(base, 180f, 180f, 180f, 0.65f, 0f, 0f, true)
        val noisy = AiColor.mix(base, 180f, 180f, 180f, 0.65f, 12f, 0f, true)
        assertTrue(AiColor.red(noisy) - 100 < AiColor.red(quiet) - 100)
        val scene = AiColor.scene(IntArray(16 * 16) { base }, 16, 16)
        assertEquals(0f, scene.noiseEstimate, 0f)
        assertEquals(0f, scene.edgeFraction, 0f)
        assertEquals(100f, scene.meanLuma, 0.001f)
        val tiny = AiColor.scene(intArrayOf(base), 1, 1)
        assertTrue(tiny.noiseEstimate.isFinite() && tiny.edgeFraction.isFinite())
    }
}
