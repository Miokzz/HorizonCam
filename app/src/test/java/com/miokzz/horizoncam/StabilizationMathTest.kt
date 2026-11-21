package com.miokzz.horizoncam

import com.miokzz.horizoncam.sensors.OrientationHistory
import com.miokzz.horizoncam.sensors.RollFusion
import com.miokzz.horizoncam.stabilization.AngleMath
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class StabilizationMathTest {
    @Test fun crossingPlusMinus180IsSmooth() {
        assertEquals(2.0, AngleMath.delta(179.0, -179.0), 0.0001)
        val timeline = OrientationHistory()
        timeline.append(100_000_000L, 170.0)
        timeline.append(120_000_000L, -170.0)
        assertEquals(180.0, timeline.sample(110_000_000L, true).rollDegrees, 0.0001)
    }

    @Test fun syncRequiresActualBracketingWithoutStaleGaps() {
        val timeline = OrientationHistory()
        timeline.append(100_000_000L, 20.0)
        timeline.append(120_000_000L, 80.0)
        assertEquals(50.0, timeline.sample(110_000_000L, true).rollDegrees, 0.0001)
        assertTrue(timeline.sample(110_000_000L, true).validTimestamp)
        assertTrue(timeline.sample(120_000_000L, true).validTimestamp)
        assertFalse(timeline.sample(120_000_001L, true).validTimestamp)
        assertFalse(timeline.sample(99_999_999L, true).validTimestamp)
        assertFalse(timeline.sample(9_000_000_000L, true).validTimestamp)
        timeline.append(900_000_000L, 140.0)
        assertFalse(timeline.sample(500_000_000L, true).validTimestamp)
        val unknown = timeline.sample(123L, false, 950_000_000L)
        assertFalse(unknown.validTimestamp)
        assertEquals(50.0, unknown.ageMs, 0.001)
    }

    @Test fun outOfOrderAndInvalidSamplesCannotAlterHistory() {
        val timeline = OrientationHistory()
        timeline.append(100_000_000L, 20.0)
        timeline.append(100_000_000L, 90.0)
        timeline.append(90_000_000L, 120.0)
        timeline.append(110_000_000L, Double.NaN)
        assertEquals(20.0, timeline.latest(), 0.0)
        assertFalse(timeline.isFresh(90_000_000L))
    }

    @Test fun degenerateProjectionFreezesWithoutInventingFreshLockOrGyroDrift() {
        val f = RollFusion()
        assertNull(f.onGravity(0.01, 0.02, 1_000_000_000L))
        assertFalse(f.gravityValid)
        f.onGravity(-9.81, 0.0, 1_010_000_000L)
        assertEquals(-90.0, f.currentDegrees(), 0.001)
        assertNull(f.onGravity(0.05, 0.05, 1_020_000_000L))
        assertFalse(f.gravityValid)
        assertEquals(-90.0, f.currentDegrees(), 0.001)
        assertNotNull(f.onGravity(0.0, 9.81, 1_030_000_000L))
        assertTrue(f.gravityValid)
        assertEquals(-180.0, AngleMath.wrap(f.currentDegrees()), 0.001)
    }

    @Test fun actualSyntheticWorldHorizonStaysLevelThroughBoth360DegreeDirections() {
        for (direction in listOf(-1, 1)) {
            val fusion = RollFusion()
            val state = HorizonState()
            state.setCameraRealtimeTimestamp(true)
            // Start at natural portrait and rotate physically in either direction.
            for (step in 0..144) {
                val theta = Math.toRadians(step * 2.5 * direction)
                val t = 1_000_000_000L + step * 10_000_000L
                // World UP in device axes from a synthetic 3D rotation matrix.
                // Projection<g simulates pitch without changing observed roll.
                val upX = 6.0 * sin(theta)
                val upY = 6.0 * cos(theta)
                val reading = fusion.onGravity(-upX, -upY, t)!!
                state.pushRoll(t, reading.rollDegrees.toFloat())
                val frame = state.frameGeometry(t)
                assertTrue(frame.timestampValid)
                // CameraX target90 axes: R(+90) maps device image horizon to
                // (sin(theta),cos(theta)) in the uncorrected landscape image.
                val rawX = sin(theta)
                val rawY = cos(theta)
                for (aspect in listOf(16.0/9, 9.0/16, 4.0/3, 3.0/4)) {
                    val m = AngleMath.correctionMatrix(frame.rotationDegrees.toDouble(), 1.0, aspect)
                    val corrected = m.apply(rawX / aspect, rawY)
                    assertEquals("rotation=$theta aspect=$aspect", 0.0, corrected.second, 0.00001)
                    assertTrue("Horizon turned upside down", corrected.first * aspect > 0.9999)
                }
            }
        }
    }

    @Test fun sensorMountAndDownstreamRotationNeverDoubleCorrectOrInvertTheHorizon() {
        fun rotate(degrees: Double, x: Double, y: Double): Pair<Double, Double> {
            val r = Math.toRadians(degrees)
            return Pair(cos(r)*x - sin(r)*y, sin(r)*x + cos(r)*y)
        }
        for (mount in listOf(90.0, 270.0)) {
            for (bufferRotation in listOf(0.0, 90.0, -90.0, 180.0)) {
                for (physicalPose in -360..360 step 5) {
                    val theta = Math.toRadians(physicalPose.toDouble())
                    val state = HorizonState()
                    val fusion = RollFusion()
                    val reading = fusion.onGravity(-9.81*sin(theta), -9.81*cos(theta), 1_000_000_000L)!!
                    state.pushRoll(reading.timestampNs, reading.rollDegrees.toFloat())
                    // A complete physical pipeline, including sensor mounting,
                    // actual native effect buffer, and CameraX's downstream rotation.
                    val deviceRight = Pair(cos(theta), -sin(theta))
                    val nativeRight = rotate(mount + bufferRotation, deviceRight.first, deviceRight.second)
                    val downstream = 90.0 - mount - bufferRotation
                    for (aspect in listOf(16.0/9, 9.0/16, 4.0/3, 3.0/4)) {
                        val m = AngleMath.correctionMatrix(state.correctionDegrees().toDouble(), 1.0, aspect)
                        val corrected = m.apply(nativeRight.first / aspect, nativeRight.second)
                        val final = rotate(downstream, corrected.first * aspect, corrected.second)
                        assertEquals("S=$mount B=$bufferRotation pose=$physicalPose", 0.0, final.second, 0.00001)
                        assertTrue("World upside down at S=$mount pose=$physicalPose", final.first > 0.9999)
                    }
                }
            }
        }
    }

    @Test fun perOutputPixelMetricPreservesCirclesAndDistancesIncludingTransposedFrames() {
        for (aspect in listOf(16.0/9, 9.0/16, 4.0/3, 3.0/4, 1.0)) {
            val zoom = AngleMath.safeFixedCrop(aspect)
            for (degree in -360..360 step 5) {
                // Exercise the EXACT column-major matrix consumed by GLES.
                val gl = AngleMath.correctionGlMatrix(degree.toDouble(), zoom, aspect)
                for (point in 0..355 step 5) {
                    val r = Math.toRadians(point.toDouble())
                    val x = cos(r) / aspect
                    val y = sin(r)
                    val px = aspect * (gl[0] * x + gl[4] * y)
                    val py = gl[1] * x + gl[5] * y
                    assertEquals("circle aspect=$aspect angle=$degree", zoom, hypot(px, py), 0.000001)
                }
            }
        }
    }

    @Test fun fixedCropCoversEveryOutputPixelCornerWithoutBlackWedges() {
        for (aspect in listOf(16.0/9, 9.0/16, 4.0/3, 3.0/4)) {
            val zoom = AngleMath.safeFixedCrop(aspect)
            for (degree in -360..360 step 3) {
                val inv = AngleMath.correctionMatrix(degree.toDouble(), zoom, aspect).inverse()
                for (x in listOf(-1.0, 1.0)) for (y in listOf(-1.0, 1.0)) {
                    val p = inv.apply(x, y)
                    assertTrue("aspect=$aspect deg=$degree p=$p", abs(p.first) <= 1.000001 && abs(p.second) <= 1.000001)
                }
            }
        }
    }

    @Test fun androidYDownMetadataRotationIsConvertedToGlYUp() {
        val cw90 = floatArrayOf(0f, -2f, 600f, 2f, 0f, 100f, 0f, 0f, 1f)
        assertEquals(-90f, AngleMath.sensorToBufferGlRotation(cw90), 0.001f)
        val identityCrop = floatArrayOf(0.5f, 0f, -20f, 0f, 0.5f, -10f, 0f, 0f, 1f)
        assertEquals(0f, AngleMath.sensorToBufferGlRotation(identityCrop), 0.001f)
    }
}
