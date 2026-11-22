package com.miokzz.horizoncam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoZoomScaleTest {
    @Test fun reachesBothEndpointsAndAllocatesRoomToLowZoom() {
        assertEquals(0.6f, PhotoZoomScale.zoom(0, 0.6f), 0.0001f)
        assertEquals(30f, PhotoZoomScale.zoom(PhotoZoomScale.STEPS, 0.6f), 0.0001f)
        assertTrue(PhotoZoomScale.progress(3f, 0.6f) - PhotoZoomScale.progress(1f, 0.6f) > 250)
        assertEquals(1f, PhotoZoomScale.zoom(0, 1f), 0.0001f)
    }

    @Test fun sliderCanRoundTripLensPresetsAndHighZoom() {
        for (zoom in listOf(0.6f, 1f, 3f, 10f, 20f, 30f)) {
            val applied = PhotoZoomScale.zoom(PhotoZoomScale.progress(zoom, 0.6f), 0.6f)
            assertEquals(zoom, applied, zoom * 0.0021f)
        }
    }
}
