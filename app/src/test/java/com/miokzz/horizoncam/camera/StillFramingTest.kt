package com.miokzz.horizoncam.camera

import com.miokzz.horizoncam.ai.RestorationStrength
import org.junit.Assert.*
import org.junit.Test

class StillFramingTest {
    @Test fun everyTenthPreservesSingleRequestedFieldOfViewWithSeveralHalCeilings() {
        for (max in listOf(1f, 2.3f, 4f, 10f, 30f, 100f)) for (i in 10..300) {
            val target = i / 10f
            val hardware = minOf(target, max)
            val residual = StillFraming.digitalRatio(target, hardware)
            assertEquals(target, hardware * residual, 0.00001f)
            for ((w, h) in listOf(4032 to 3024, 3024 to 4032)) {
                val rect = StillFraming.centerCrop(w, h, residual)
                assertTrue(kotlin.math.abs(w / residual - rect.width) <= 0.51f)
                assertTrue(kotlin.math.abs(h / residual - rect.height) <= 0.51f)
                assertTrue(kotlin.math.abs(w - 2 * rect.left - rect.width) <= 1)
                assertTrue(kotlin.math.abs(h - 2 * rect.top - rect.height) <= 1)
            }
        }
    }
    @Test fun nativeAndDigitalGridsUseOneCropAndRespectModelBudget() {
        assertEquals(StillFraming.Crop(0, 0, 400, 300), StillFraming.centerCrop(400,300,StillFraming.digitalRatio(2.3f,2.3f)))
        assertEquals(StillFraming.Crop(150, 112, 100, 75), StillFraming.centerCrop(400,300,4f))
        assertEquals(4, StillFraming.reconstructionScale(400,300,10f,12_600_000))
        assertEquals(2, StillFraming.reconstructionScale(1700,1200,8f,12_600_000))
        assertEquals(1, StillFraming.reconstructionScale(4032,3024,1f,12_600_000))
        assertEquals(3f, StillFraming.digitalRatio(100f,10f),0f)
        assertTrue(RestorationStrength.forCapture(1f,100) < RestorationStrength.forCapture(2.3f,100))
        assertTrue(RestorationStrength.forCapture(30f,3200) < 1f)
    }
}
