package com.miokzz.horizoncam.stabilization

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

class HighZoomMotionTest {
    @Test
    fun matcherReducesResidualShakeInTheSampledTeleCrop() {
        // 960×720 analysis stream divided by the 10× residual crop of a 30× photo.
        val width = 96
        val height = 72
        val tracker = FrameMotion(width, height)
        var rawEnergy = 0.0
        var correctedEnergy = 0.0
        var confident = 0
        for (frame in 0 until 48) {
            val dx = 1.55 * sin(frame * PI / 2)
            val dy = 0.80 * cos(frame * PI / 2)
            val exposureOffset = if (frame % 2 == 0) 4 else -4
            val input = tracker.input
            for (y in 0 until height) for (x in 0 until width) {
                val px = x - dx
                val py = y - dy
                val luminance = 128 + 21 * sin(px * 0.58 + py * 0.19) +
                    25 * cos(px * 0.23 - py * 0.63) + 25 * sin(px * 0.41 + py * 0.46) + exposureOffset
                input[y * width + x] = luminance.roundToInt().toByte()
            }
            val correction = tracker.advance((frame + 1) * 33_333_333L)
            if (frame < 8) continue
            val stabilizedX = dx + correction.x * width / 2
            val stabilizedY = dy + correction.y * height / 2
            rawEnergy += dx * dx + dy * dy
            correctedEnergy += stabilizedX * stabilizedX + stabilizedY * stabilizedY
            if (tracker.matchedPatches >= 4) confident++
        }
        val retainedShake = sqrt(correctedEnergy / rawEnergy)
        assertTrue("matches=$confident retained RMS=$retainedShake", confident >= 36 && retainedShake < 0.5)
    }
}
