package com.miokzz.horizoncam.camera

import org.junit.Assert.*
import org.junit.Test

class StillPreviewGeometryTest {
    private val main = LensZoomSpec("logical",1f,0.6f,10f)
    private val physical = listOf(main,LensZoomSpec("wide",0.594f,1f,1f,true),
        LensZoomSpec("primary",1f,1f,1f,true),LensZoomSpec("tele",2.9f,1f,1f,true))

    @Test fun requestedBandSelectsEachDedicatedSensorAndKeepsTotalFraming() {
        for (target in listOf(0.6f,0.9f,1f,2f,2.3f,2.9f,3f,5f,7.8f,10f,16.5f,20f,30f)) {
            val route=ZoomRouter.route(target,"logical","logical",physical,false,stillPhoto=true)
            assertEquals(when { target<1f -> "wide"; target<3f -> "primary"; else -> "tele" },route.targetKey)
            val crop=StillFraming.digitalRatio(target,route.displayZoom)
            val confirmed=StillPreviewGeometry.confirmed(target,route.displayZoom,1f)
            assertEquals(target,confirmed.first,0.00001f)
            assertEquals(crop,confirmed.second,0.00001f)
            // The displayed window and JPEG source crop have the same normalized center/extent.
            val visibleEdge=StillPreviewGeometry.visibleToLocal(0f,1080f,crop)/1080f
            assertEquals((1f-1f/crop)/2,visibleEdge,0.00001f)
        }
    }

    @Test fun nominalThreeAttemptsNearThreeTeleThenReportsItsRealMinimum() {
        val tele=LensZoomSpec("tele",3.05f,1f,1f,true)
        val route=ZoomRouter.route(3f,"logical","logical",listOf(main,tele),false,stillPhoto=true)
        assertEquals("tele",route.targetKey)
        assertTrue(route.limited)
        assertEquals(3.05f,route.displayZoom,0.00001f)
    }

    @Test fun rejectedDedicatedSensorFallsBackWithoutLosingRequestedCrop() {
        val route=ZoomRouter.route(30f,"tele","logical",physical,false,setOf("tele"),stillPhoto=true)
        assertEquals("logical",route.targetKey)
        assertEquals(30f,route.displayZoom * StillFraming.digitalRatio(30f,route.displayZoom),0.00001f)
        val recording=ZoomRouter.route(30f,"wide","logical",physical,true,stillPhoto=true)
        assertFalse(recording.requiresRebind)
        assertEquals("wide",recording.targetKey)
    }

    @Test fun tapCoordinatesRoundTripAcrossDigitalCropForBothOrientations() {
        for (size in listOf(1080f,1440f,1920f)) for (crop in listOf(1f,2f,10.344827f)) for (fraction in listOf(0f,.1f,.5f,.9f,1f)) {
            val local=StillPreviewGeometry.visibleToLocal(size*fraction,size,crop)
            assertEquals(size*fraction,StillPreviewGeometry.localToVisible(local,size,crop),0.001f)
        }
    }

    @Test fun cameraRecoveryIsBoundedAndBacksOff() {
        assertTrue(StillPreviewGeometry.retryDelay(0)!!<StillPreviewGeometry.retryDelay(1)!!)
        assertTrue(StillPreviewGeometry.retryDelay(1)!!<StillPreviewGeometry.retryDelay(2)!!)
        assertNull(StillPreviewGeometry.retryDelay(3))
    }
}
