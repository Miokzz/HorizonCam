package com.miokzz.horizoncam

import com.miokzz.horizoncam.ai.DetailTransfer
import com.miokzz.horizoncam.ai.PhotoProvenance
import com.miokzz.horizoncam.ai.PhotoPrior
import com.miokzz.horizoncam.ai.ReconstructionPlan
import com.miokzz.horizoncam.ai.SceneEvidence
import org.junit.Assert.*
import org.junit.Test

class PhotoReconstructionTest {
    private val daylight = SceneEvidence(1f, 0.4f, 0.35f, 135f, 0.002, 100)
    private fun gray(value: Int) = (255 shl 24) or (value * 0x010101)

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
        fun transfer(prediction: Int, native: Boolean) = DetailTransfer.pixel(
            gray(120), gray(120), gray(prediction), 120, 120, 120, 120, 12f, daylight, true, native)
        assertEquals(gray(120), transfer(120, true))
        val native = DetailTransfer.y(transfer(146, true))
        val enlarged = DetailTransfer.y(transfer(146, false))
        assertTrue(native in 121 until enlarged)
        assertTrue(enlarged <= 146)
    }

    @Test fun preservesMeasuredTextureOnlyUnderClearNativeCaptureConditions() {
        val texture = daylight.copy(noise = 7f, texture = 0.92f, sharpFraction = 0.68f)
        assertTrue(texture.preserveNative(1f))
        assertFalse(texture.preserveNative(4f))
        assertFalse(texture.copy(iso = 1200).preserveNative(1f))
        assertFalse(texture.copy(iso = 0).preserveNative(1f))
        assertFalse(texture.copy(shutterSeconds = 0.0).preserveNative(1f))
        assertFalse(texture.copy(shutterSeconds = 1.0 / 15).preserveNative(1f))
        assertFalse(texture.copy(sharpFraction = 0.43f).preserveNative(1f))
    }

    @Test fun constrainsUnsupportedDarkAndBrightStrokesInEitherPolarity() {
        for (tone in listOf(52, 195)) {
            for (prediction in listOf(tone - 42, tone + 42)) {
                val restored = DetailTransfer.pixel(
                    gray(tone), gray(tone), gray(prediction), tone, tone, tone, tone,
                    30f, daylight, true, false, tone - 2, tone + 2)
                assertTrue(DetailTransfer.y(restored) in tone - 5..tone + 5)
            }
        }
    }

    @Test fun priorMeanAnchorsToneWhileKeepingPositiveAndNegativeDetails() {
        fun restored(prediction: Int, mean: Int) = DetailTransfer.y(DetailTransfer.pixel(
            gray(110), gray(110), gray(prediction), 96, 120, 105, 110, 30f, daylight,
            true, false, 73, 140, mean))
        val brighter = restored(139, 122)
        val darker = restored(101, 122)
        assertTrue(brighter in 111 until restored(139, -1))
        assertTrue(darker < restored(101, -1))
    }

    @Test fun provenanceDescribesOnlyProcessingThatOccurred() {
        fun description(model: String?, frames: Int, scale: Int) =
            PhotoProvenance.describe(model, frames, 30f, 296, 394, 296 * scale, 394 * scale)
        val native = description(null, 1, 1)
        assertEquals(PhotoProvenance.Kind.NATIVE, PhotoProvenance.classify(native))
        assertTrue("input/output 296x394 -> 296x394" in native)
        assertFalse("Horizon AI" in native)
        assertFalse("textura estimada" in native)
        assertEquals(PhotoProvenance.Kind.FUSION, PhotoProvenance.classify(description(null, 2, 1)))
        val neural = description(PhotoPrior.MAX.display, 2, 4)
        assertEquals(PhotoProvenance.Kind.NEURAL, PhotoProvenance.classify(neural))
        assertTrue("frames=2 | input/output 296x394 -> 1184x1576" in neural)
        assertEquals(PhotoProvenance.Kind.CAMERA, PhotoProvenance.classify(""))
        val resized = PhotoProvenance.describe(null, 1, 30f, 264, 353, 1056, 1412,
            conventionalResize = true)
        assertEquals(PhotoProvenance.Kind.RESIZED, PhotoProvenance.classify(resized))
        assertTrue("input/output 264x353 -> 1056x1412" in resized)
        assertFalse("textura estimada" in resized)
    }
}
