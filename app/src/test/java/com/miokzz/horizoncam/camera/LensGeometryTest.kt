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
}
