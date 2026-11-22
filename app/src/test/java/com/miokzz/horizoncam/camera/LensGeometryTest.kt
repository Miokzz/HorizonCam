package com.miokzz.horizoncam.camera

import org.junit.Assert.*
import org.junit.Test

class LensGeometryTest {
    @Test fun relativeZoomUsesRectilinearScaleRatherThanRatioOfAngles() {
        assertEquals(2.9263f, LensGeometry.relativeZoom(80.0, 32.0)!!, 0.001f)
        assertEquals(0.48445f, LensGeometry.relativeZoom(80.0, 120.0)!!, 0.001f)
        assertEquals(1f, LensGeometry.relativeZoom(80.0, 80.0)!!, 0f)
    }

    @Test fun invalidMetadataDoesNotProduceLensClaim() {
        assertNull(LensGeometry.relativeZoom(null, 50.0))
        assertNull(LensGeometry.relativeZoom(80.0, 0.0))
        assertNull(LensGeometry.relativeZoom(80.0, 180.0))
        assertNull(LensGeometry.relativeZoom(Double.NaN, 50.0))
    }

    @Test fun baseFieldUsesCommonFourByThreeWindowBeforeComparingLenses() {
        val nativeFourByThree = LensGeometry.stillHorizontalFov(5.4, 8.16, 6.12)
        val sameHeightWithWiderSensor = LensGeometry.stillHorizontalFov(5.4, 9.0, 6.12)
        assertEquals(1f, LensGeometry.relativeZoom(nativeFourByThree, sameHeightWithWiderSensor)!!, 0.001f)
        val tele = LensGeometry.stillHorizontalFov(7.0, 3.648, 2.736)
        assertEquals(2.90f, LensGeometry.relativeZoom(nativeFourByThree, tele)!!, 0.003f)
    }
}
