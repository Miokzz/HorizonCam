package com.miokzz.horizoncam

import com.miokzz.horizoncam.sensors.OrientationHistory
import com.miokzz.horizoncam.sensors.RollFusion
import com.miokzz.horizoncam.stabilization.AngleMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StabilizationMathTest {
    @Test fun crossingPlusMinus180IsSmooth() {
        assertEquals(2.0, AngleMath.delta(179.0, -179.0), 0.0001)
        assertEquals(-2.0, AngleMath.delta(-179.0, 179.0), 0.0001)
        val timeline = OrientationHistory()
        timeline.append(100_000_000L, 170.0)
        timeline.append(200_000_000L, -170.0)
        assertEquals(180.0, timeline.sample(150_000_000L, true).rollDegrees, 0.0001)
    }

    @Test fun timestampsInterpolateBetweenSensorSamples() {
        val timeline = OrientationHistory()
        timeline.append(100_000_000L, 20.0)
        timeline.append(200_000_000L, 80.0)
        assertEquals(50.0, timeline.sample(150_000_000L, true).rollDegrees, 0.0001)
        assertTrue(timeline.sample(150_000_000L, true).validTimestamp)
        assertFalse(timeline.sample(9_000_000_000L, true).validTimestamp)
        assertFalse(timeline.sample(150_000_000L, false).validTimestamp)
    }

    @Test fun gyroContinuesWhenGravityProjectionIsDegenerate() {
        val f = RollFusion(gravityGain = 0.5)
        f.onGravity(0.0, -9.81, 1_000_000_000L)
        f.onGyroscope(-Math.PI / 2.0, 1_000_000_001L)
        for (i in 1..100) {
            f.onGyroscope(-Math.PI / 2.0, 1_000_000_001L + i * 10_000_000L)
            f.onGravity(0.05, 0.05, 1_000_000_002L + i * 10_000_000L)
        }
        assertFalse(f.gravityValid)
        assertTrue(abs(f.currentDegrees() - 90.0) < 4.0)
    }

    @Test fun fixedCropCoversEveryRotatedFrameCorner() {
        val aspect = 16.0 / 9.0
        val zoom = AngleMath.safeFixedCrop(aspect)
        for (degree in 0..360 step 3) {
            val inv = AngleMath.correctionMatrix(degree.toDouble(), zoom, aspect).inverse()
            for (x in listOf(-1.0, 1.0)) for (y in listOf(-1.0, 1.0)) {
                val p = inv.apply(x, y)
                assertTrue("deg=$degree p=$p", abs(p.first) <= 1.001 && abs(p.second) <= 1.001)
            }
        }
    }

    @Test fun twoOutputTargetsShareCanonicalGeometry() {
        val state = HorizonState()
        state.pushRoll(1_000_000_000L, 90f)
        state.pushRoll(1_020_000_000L, 130f)
        val preview = state.frameGeometry(1_010_000_000L)
        val encoder = state.frameGeometry(1_010_000_000L)
        assertEquals(preview, encoder)
        assertEquals(-20f, preview.rotationDegrees, 0.01f)
        assertTrue(preview.timestampValid)
    }
}
