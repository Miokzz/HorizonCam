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

    @Test fun sliderUsesTenthsAndKeepsBothEndpoints() {
        assertEquals(2.8f, PhotoZoomScale.tenths(2.89f), 0f)
        assertEquals(6.7f, PhotoZoomScale.tenths(6.79f), 0f)
        assertEquals(0.6f, PhotoZoomScale.tenths(PhotoZoomScale.zoom(0, 0.6f)), 0f)
        assertEquals(30f, PhotoZoomScale.tenths(PhotoZoomScale.zoom(PhotoZoomScale.STEPS, 0.6f)), 0f)
    }

    @Test fun integerDetentHoldsForHalfASecondAndDoesNotBlockFastSweeps() {
        val detents = PhotoZoomDetents()
        detents.start(2.8f)
        assertEquals(3f, detents.update(3.03f, 100), 0f)
        assertEquals(3f, detents.update(3.27f, 599), 0f)
        assertEquals(3.2f, detents.update(3.27f, 600), 0f)
        detents.start(7.2f)
        assertEquals(7f, detents.update(6.97f, 1000), 0f)
        assertEquals(6.2f, detents.update(6.23f, 1050), 0f)
        assertEquals(14.5f, detents.update(14.56f, 1060), 0f)
    }
}
