package com.miokzz.horizoncam.camera

import com.miokzz.horizoncam.ai.RestorationStrength
import org.junit.Assert.*
import org.junit.Test

class StillOpticsTest {
    private val lenses = listOf(LensZoomSpec("logical",1f,0.6f,10f), LensZoomSpec("tele",3f,1f,1f,true))
    @Test fun physicalTeleWhenItCanFrameTheSubjectOtherwiseUseLogical() {
        for ((zoom, best) in listOf(0.6f to "logical",1f to "logical",2.3f to "logical",7.8f to "tele",16.5f to "tele",30f to "tele")) {
            val route = ZoomRouter.route(zoom,"logical","logical",lenses,false,stillPhoto=true)
            assertEquals(best, route.targetKey)
            assertEquals(zoom, route.displayZoom * StillFraming.digitalRatio(zoom,route.displayZoom),0.0001f)
        }
        assertEquals("logical",ZoomRouter.route(16.5f,"logical","logical",lenses,false,setOf("tele"),stillPhoto=true).targetKey)
    }
    @Test fun aggressivePriorCannotReplaceFlatTextOrSkinSignals() {
        for (measured in listOf(0xff333333.toInt(),0xffd6a689.toInt(),0xffeeeeee.toInt())) {
            for (prior in listOf(-1,0xff000000.toInt(),0xff00ff00.toInt())) {
                val result = RestorationStrength.protect(measured,prior,measured,0.86f)
                for (shift in listOf(0,8,16)) assertTrue(kotlin.math.abs(
                    (result shr shift and 255) - (measured shr shift and 255)) <= 13)
            }
        }
    }
}
