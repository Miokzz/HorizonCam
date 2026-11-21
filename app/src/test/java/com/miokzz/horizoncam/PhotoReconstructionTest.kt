package com.miokzz.horizoncam

import com.miokzz.horizoncam.ai.DetailTransfer
import com.miokzz.horizoncam.ai.PhotoPrior
import com.miokzz.horizoncam.ai.ReconstructionPlan
import com.miokzz.horizoncam.ai.SceneEvidence
import org.junit.Assert.*
import org.junit.Test

class PhotoReconstructionTest {
    private val daylight = SceneEvidence(1f, 0.4f, 0.35f, 135f, 0.002, 100)

    @Test fun choosesAnEngineWithoutChangingTheCapturedField() {
        val fast = ReconstructionPlan.choose(4080, 3060, 4080, 3060, 1f, daylight)
        assertEquals(PhotoPrior.FAST, fast.prior)
        assertEquals(80, fast.tileCount)
        assertEquals(4 * fast.guideH, 3 * fast.guideW)
        val tele = ReconstructionPlan.choose(401, 301, 1604, 1204, 30f, daylight)
        assertEquals(PhotoPrior.MAX, tele.prior)
        assertEquals(401, tele.guideW)
        assertEquals(301, tele.guideH)
        assertEquals(4, tele.neuralScale)
        assertEquals(42, tele.tileCount)
    }

    @Test fun preservesFlatFieldsAndTransfersMoreDetailOnAnEnlargedCrop() {
        fun grey(n: Int) = (255 shl 24) or (n * 0x010101)
        fun transfer(prediction: Int, native: Boolean) = DetailTransfer.pixel(
            grey(120), grey(120), grey(prediction), 120, 120, 120, 120, 12f, daylight, true, native)
        assertEquals(grey(120), transfer(120, true))
        val native = DetailTransfer.y(transfer(146, true))
        val enlarged = DetailTransfer.y(transfer(146, false))
        assertTrue(native in 121 until enlarged)
        assertTrue(enlarged <= 146)
    }
}
