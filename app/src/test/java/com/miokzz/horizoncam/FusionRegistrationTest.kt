package com.miokzz.horizoncam

import com.miokzz.horizoncam.photo.fusion.FusionMath
import com.miokzz.horizoncam.photo.fusion.FusionRegistration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionRegistrationTest {
    @Test
    fun reusesIntegerAndSubpixelCoordinatesWithoutChangingScoreBits() {
        for ((width, height, margin) in listOf(Triple(37, 28, 3), Triple(264, 353, 18), Triple(640, 104, 26))) {
            val reference = FloatArray(width * height) { i ->
                if (i % 101 == 0) 255f else (20 + (i % width * 9 + i / width * 3) % 192).toFloat() + 0.13f
            }
            val candidate = FloatArray(reference.size) { i ->
                if (i % 173 == 0) 0f else reference[i] * 0.997f + (i % 17 - 8) * 0.17f
            }
            var informative = false
            for (gain in listOf(0.75, 1.0, 1.23)) {
                val prepared = FusionRegistration.Correlation(reference, candidate, width, height, margin, gain)
                for ((dx, dy) in listOf(0.0 to 0.0, 3.0 to -2.0, -16.0 to 27.0, 29.0 to -30.0,
                    -0.15 to 0.05, 7.95 to -3.5, -25.75 to 0.0, 31.05 to -28.2, 1.0 to 0.05)) {
                    val expected = originalScore(reference, candidate, width, height, margin, gain, dx, dy)
                    val actual = prepared.score(dx, dy)
                    assertEquals("$width×$height margem $margin ganho $gain deslocamento $dx,$dy",
                        expected.toRawBits(), actual.toRawBits())
                    informative = informative || expected != -1.0
                }
            }
            assertTrue("A amostra precisa incluir pontuações válidas.", informative)
        }
    }

    private fun originalScore(
        reference: FloatArray,
        candidate: FloatArray,
        width: Int,
        height: Int,
        margin: Int,
        gain: Double,
        dx: Double,
        dy: Double,
    ): Double {
        val step = max(1, max(width, height) / 160)
        var sumReference = 0.0
        var sumCandidate = 0.0
        var squareReference = 0.0
        var squareCandidate = 0.0
        var product = 0.0
        var count = 0
        var possible = 0
        for (y in margin until height - margin step step) for (x in margin until width - margin step step) {
            val a = reference[y * width + x].toDouble()
            val b = FusionMath.sample(candidate, width, height, x + dx, y + dy)
            if (a < 8 || a > 247 || b < 4 || b > 251) continue
            possible++
            if (abs(a - b * gain) > 40.0) continue
            sumReference += a
            sumCandidate += b
            squareReference += a * a
            squareCandidate += b * b
            product += a * b
            count++
        }
        if (count < 32 || count < possible * 0.60) return -1.0
        val varianceReference = squareReference - sumReference * sumReference / count
        val varianceCandidate = squareCandidate - sumCandidate * sumCandidate / count
        if (varianceReference <= 1 || varianceCandidate <= 1) return -1.0
        val correlation = (product - sumReference * sumCandidate / count) / sqrt(varianceReference * varianceCandidate)
        return correlation - 0.08 * (1.0 - count.toDouble() / max(1, possible))
    }
}
