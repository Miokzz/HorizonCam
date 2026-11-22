package com.miokzz.horizoncam.camera

import org.junit.Assert.*
import org.junit.Test

class LockLensPolicyTest {
    private val options = listOf(
        LensZoomSpec("logical",1f,.6f,10f),
        LensZoomSpec("wide",.594f,1f,1f,true),
        LensZoomSpec("primary",1f,1f,1f,true),
        LensZoomSpec("tele",2.9f,1f,1f,true)
    )
    @Test fun enteringLockPrefersTheActualUltrawideStream() {
        val route=LockLensPolicy.route(options,"logical","tele",emptySet())
        assertEquals("wide",route.targetKey)
        assertEquals(.594f,route.displayZoom,.00001f)
        assertEquals(1f,route.cameraZoomRatio,0f)
        assertTrue(route.requiresRebind)
    }
    @Test fun refusedUltrawideFallsBackToLogicalWideThenPrimary() {
        val logical=LockLensPolicy.route(options,"logical","tele",setOf("wide"))
        assertEquals("logical",logical.targetKey)
        assertEquals(.6f,logical.displayZoom,.00001f)
        val restricted=options.map { if(it.key=="logical") it.copy(minCameraZoom=1f) else it }
        assertEquals(1f,LockLensPolicy.route(restricted,"logical","tele",setOf("wide")).displayZoom,0f)
    }
    @Test fun explicitLensAndRecordingCannotBeOverriddenByAutomaticWideSelection() {
        val explicit=LockLensPolicy.route(options,"logical","tele",emptySet(),explicit=true)
        val recording=LockLensPolicy.route(options,"logical","tele",emptySet(),recording=true)
        for (route in listOf(explicit,recording)) {
            assertEquals("tele",route.targetKey)
            assertFalse(route.requiresRebind)
        }
    }
    @Test fun meteringFootprintRemainsInsideTheVisibleDigitalWindow() {
        for (crop in listOf(1f,2f,10.344827f)) {
            val size=StillPreviewGeometry.meteringSize(crop)
            assertTrue(size>0)
            assertEquals(.15f,size*crop,.00001f)
        }
    }
}
