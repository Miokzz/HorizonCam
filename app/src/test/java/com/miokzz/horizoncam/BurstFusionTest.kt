package com.miokzz.horizoncam

import com.miokzz.horizoncam.photo.fusion.*
import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class BurstFusionTest {
    private fun signal(x: Double, y: Double, detail: Boolean = false): Double {
        val base = 125 + 32 * sin(x * 0.19) + 25 * cos(y * 0.15) + 23 * sin((x + y) * 0.11)
        return base + if (detail) 8 * sin(PI * x) + 8 * sin(PI * y) else 0.0
    }

    private fun pixel(value: Double): Int {
        val v = value.roundToInt().coerceIn(0, 255)
        return (255 shl 24) or (v shl 16) or (v shl 8) or v
    }

    private fun frame(
        width: Int = 96,
        height: Int = 80,
        dx: Double = 0.0,
        dy: Double = 0.0,
        gain: Double = 1.0,
        noise: Double = 0.0,
        seed: Long = 0,
        detail: Boolean = false,
    ): FusionFrame {
        val random = Random(seed)
        return FusionFrame(
            IntArray(width * height) { index ->
                pixel(
                    signal(index % width + dx, index / width + dy, detail) * gain +
                        random.nextGaussian() * noise
                )
            },
            width,
            height,
            exposureTimeNs = (10_000_000 * gain).toLong(),
            iso = 100,
        )
    }

    private fun mse(
        a: IntArray,
        b: IntArray,
        margin: Int = 0,
        width: Int = 96,
        height: Int = 80,
    ): Double {
        var sum = 0.0
        var count = 0
        for (y in margin until height - margin) for (x in margin until width - margin) {
            val difference = (a[y * width + x] and 255) - (b[y * width + x] and 255)
            sum += difference * difference
            count++
        }
        return sum / count
    }

    @Test
    fun sameFieldIdentityHasNoInventedShiftOrResolution() {
        val source = frame()
        val result =
            BurstFusion.fuse(
                List(5) { source.copy(pixels = source.pixels.clone()) },
                FusionOptions(superResolution = true, maxShiftPixels = 6),
            )
        assertEquals(0, result.referenceIndex)
        assertEquals(5, result.acceptedFrameCount)
        assertFalse(result.superResolved)
        assertEquals(source.width, result.width)
        assertArrayEquals(source.pixels, result.pixels)
        assertEquals(0.25f, result.phaseCoverage, 0f)
    }

    @Test
    fun translationalRegistrationMeasuresSubpixelShiftAndExposure() {
        val frames = listOf(frame(), frame(dx = 2.35, dy = -1.60, gain = 0.65))
        val result = BurstFusion.fuse(frames, FusionOptions(maxShiftPixels = 6))
        val alignment = result.alignments[1]
        assertTrue(alignment.toString(), alignment.accepted)
        assertEquals(-2.35, alignment.dx, 0.20)
        assertEquals(1.60, alignment.dy, 0.20)
        assertEquals(1 / 0.65, alignment.exposureGain, 0.10)
        assertTrue(alignment.confidence > 0.95)
    }

    @Test
    fun temporalNoiseMseFallsAndMovingObjectsDoNotGhost() {
        val truth = frame()
        val burst = List(5) { frame(noise = 6.0, seed = it.toLong() + 7) }
        val result = BurstFusion.fuse(burst, FusionOptions(maxShiftPixels = 4))
        assertTrue(result.acceptedFrameCount >= 4)
        assertTrue(
            "fused=${mse(result.pixels,truth.pixels,8)} reference=${mse(burst[result.referenceIndex].pixels,truth.pixels,8)}",
            mse(result.pixels, truth.pixels, 8) <
                mse(burst[result.referenceIndex].pixels, truth.pixels, 8) * 0.65,
        )
        val moving =
            List(5) { index ->
                val current = frame()
                val pixels = current.pixels.clone()
                val origin = 20 + index * 11
                for (y in 24..34) for (x in origin until origin + 8) pixels[y * current.width + x] =
                    pixel(240.0)
                current.copy(pixels = pixels)
            }
        val motion = BurstFusion.fuse(moving, FusionOptions(maxShiftPixels = 4))
        assertEquals(0, motion.referenceIndex)
        assertTrue(
            motion.alignments.toString(),
            motion.alignments.drop(1).any { it.motionRejectedFraction > 0 },
        )
        for (y in 25..33) for (x in 21..26) assertTrue((motion.pixels[y * 96 + x] and 255) > 225)
        for (y in 25..33) for (x in 42..46) assertTrue(
            abs((motion.pixels[y * 96 + x] and 255) - (truth.pixels[y * 96 + x] and 255)) < 8
        )
    }

    @Test
    fun grossOutlierAndBlurAreRejected() {
        val original = frame()
        val random = Random(99)
        val garbage =
            original.copy(
                pixels = IntArray(original.pixels.size) { pixel(random.nextDouble() * 255) }
            )
        val blurred =
            original.copy(
                pixels =
                    IntArray(original.pixels.size) { index ->
                        var sum = 0.0
                        var count = 0
                        val x = index % 96
                        val y = index / 96
                        for (j in -10..10) for (i in -10..10) {
                            sum +=
                                original.pixels[
                                        (y + j).coerceIn(0, 79) * 96 + (x + i).coerceIn(0, 95)] and
                                    255
                            count++
                        }
                        pixel(sum / count)
                    }
            )
        val result =
            BurstFusion.fuse(listOf(original, garbage, blurred), FusionOptions(maxShiftPixels = 6))
        assertFalse(
            "reference=${result.referenceIndex} ${result.alignments}",
            result.alignments[1].accepted,
        )
        assertFalse(result.alignments.toString(), result.alignments[2].accepted)
        assertArrayEquals(original.pixels, result.pixels)
    }

    @Test
    fun highZoomBlendPreservesDetailAgainstAlignedMotionBlur() {
        val original = frame()
        val blurred = original.copy(pixels = IntArray(original.pixels.size) { index ->
            val x = index % original.width + 1.25
            val y = index / original.width - 0.6
            pixel((-4..4).map { signal(x + it, y) }.average())
        })
        val burst = listOf(original, blurred)
        val options = FusionOptions(referenceIndex = 0, maxShiftPixels = 6)
        val baseline = BurstFusion.fuse(burst, options)
        val protected = BurstFusion.fuse(burst, options.copy(protectReferenceDetail = true))
        val alignment = protected.alignments[1]
        assertTrue(alignment.toString(), alignment.accepted && alignment.sharpnessRatio in 0.40..0.90)
        assertEquals(baseline.alignments, protected.alignments)
        assertEquals(0, protected.referenceIndex)
        val baselineError = mse(baseline.pixels, original.pixels, 8)
        val protectedError = mse(protected.pixels, original.pixels, 8)
        assertTrue("baseline=$baselineError protected=$protectedError", protectedError < baselineError * 0.9)
    }

    @Test
    fun actualDistinctHalfPixelMeasurementsEnableTwoTimesReconstruction() {
        val frames =
            listOf(
                frame(detail = true),
                frame(dx = 0.5, detail = true),
                frame(dy = 0.5, detail = true),
                frame(dx = 0.5, dy = 0.5, detail = true),
            )
        val result =
            BurstFusion.fuse(frames, FusionOptions(superResolution = true, maxShiftPixels = 4))
        assertTrue(result.alignments.toString(), result.superResolved)
        assertEquals(192, result.width)
        assertEquals(160, result.height)
        assertEquals(1f, result.phaseCoverage, 0f)
        assertTrue(result.measuredSampleFraction > 0.8f)
        val reference = frames[result.referenceIndex]
        val phases = listOf(0.0 to 0.0, 0.5 to 0.0, 0.0 to 0.5, 0.5 to 0.5)
        val (offsetX, offsetY) = phases[result.referenceIndex]
        val truth =
            IntArray(result.width * result.height) { index ->
                pixel(
                    signal(
                        index % result.width / 2.0 + offsetX,
                        index / result.width / 2.0 + offsetY,
                        true,
                    )
                )
            }
        val bilinear =
            IntArray(truth.size) { index ->
                val x = index % result.width / 2.0
                val y = index / result.width / 2.0
                val x0 = floor(x).toInt().coerceAtMost(95)
                val y0 = floor(y).toInt().coerceAtMost(79)
                val x1 = (x0 + 1).coerceAtMost(95)
                val y1 = (y0 + 1).coerceAtMost(79)
                val fx = x - x0
                val fy = y - y0
                fun v(xx: Int, yy: Int) = (reference.pixels[yy * 96 + xx] and 255).toDouble()
                pixel(
                    (v(x0, y0) * (1 - fx) + v(x1, y0) * fx) * (1 - fy) +
                        (v(x0, y1) * (1 - fx) + v(x1, y1) * fx) * fy
                )
            }
        val fusedMse = mse(result.pixels, truth, 12, result.width, result.height)
        val interpolatedMse = mse(bilinear, truth, 12, result.width, result.height)
        assertTrue(
            "measured SR MSE=$fusedMse interpolation=$interpolatedMse align=${result.alignments}",
            fusedMse < interpolatedMse * 0.70,
        )
    }

    @Test
    fun integerOrOneAxisOnlyDiversityFallsBackToBaseSizedDenoise() {
        for (burst in
            listOf(
                listOf(frame(), frame(dx = 1.0), frame(dy = 1.0), frame(dx = 1.0, dy = 1.0)),
                listOf(frame(), frame(dx = 0.5), frame(), frame(dx = 0.5)),
            )) {
            val result =
                BurstFusion.fuse(burst, FusionOptions(superResolution = true, maxShiftPixels = 4))
            assertFalse(result.superResolved)
            assertEquals(96, result.width)
            assertEquals(80, result.height)
        }
    }

    @Test
    fun underexposedHighlightFrameIsEligibleButNeverBecomesNormalReference() {
        fun highlight(exposure: Double): FusionFrame {
            val base = frame(gain = exposure)
            val pixels = base.pixels.clone()
            for (y in 30..50) for (x in 30..65) pixels[y * 96 + x] =
                pixel((265 + (x - 30) * 3) * exposure)
            return base.copy(pixels = pixels)
        }
        val normal = highlight(1.0)
        val result =
            BurstFusion.fuse(
                listOf(normal, normal, highlight(0.5), normal, normal),
                FusionOptions(maxShiftPixels = 4),
            )
        assertNotEquals(2, result.referenceIndex)
        assertTrue(result.alignments[2].toString(), result.alignments[2].accepted)
        assertTrue(result.highlightRecoveredPixels > 0)
        val values = (34..61).map { result.pixels[40 * 96 + it] and 255 }
        assertTrue(values.distinct().size >= 3)
        assertTrue(values.all { it in 240..255 })
    }

    @Test
    fun dimensionsLimitsAndCancellationAreExplicit() {
        fun rejects(block: () -> Unit) {
            try {
                block()
                fail("Invalid frame accepted")
            } catch (_: IllegalArgumentException) {}
        }
        rejects { BurstFusion.fuse(emptyList()) }
        rejects { BurstFusion.fuse(List(9) { frame() }) }
        rejects { BurstFusion.fuse(listOf(frame(), frame(width = 80))) }
        rejects { BurstFusion.fuse(listOf(FusionFrame(IntArray(1), 96, 80))) }
        rejects { BurstFusion.fuse(listOf(frame(width = 1281, height = 16))) }
        rejects {
            BurstFusion.fuse(
                listOf(frame(width = 961, height = 16)),
                FusionOptions(superResolution = true),
            )
        }
        var completed = 0
        try {
            BurstFusion.fuse(
                List(5) { frame() },
                onProgress = { completed = it.completedFrames },
                isCancelled = { completed >= 2 },
            )
            fail("Cancellation ignored")
        } catch (_: CancellationException) {
            assertEquals(2, completed)
        }
    }
}
