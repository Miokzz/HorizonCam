package com.miokzz.horizoncam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizonStateTest {
    @Test fun nativeLandscapeStartsWithNoCorrection() {
        val s = HorizonState()
        s.updateRoll(90f)
        assertTrue(s.isLandscapePose())
        assertEquals(0f, s.correctionDegrees(), 0.01f)
    }

    @Test fun portraitDoesNotTurnIntoAPortraitRecording() {
        val s = HorizonState()
        s.updateRoll(0f)
        assertFalse(s.isLandscapePose())
        assertEquals(90f, s.correctionDegrees(), 0.01f)
        s.startRecording()
        s.updateRoll(90f)
        assertEquals(0f, s.correctionDegrees(), 0.01f)
    }

    @Test fun full360NeverReanchorsOrFlipsAt90() {
        val s = HorizonState()
        s.updateRoll(90f)
        s.startRecording()
        val angles = listOf(90f, 135f, 179f, -180f, -135f, -90f, -45f, 0f, 45f, 90f)
        val expected = listOf(0f, -45f, -89f, -90f, -135f, 180f, 135f, 90f, 45f, 0f)
        angles.zip(expected).forEach { (r, output) ->
            s.updateRoll(r)
            assertEquals("roll=$r", output, s.correctionDegrees(), 0.01f)
        }
        s.stopRecording()
        assertEquals(0f, s.correctionDegrees(), 0.01f)
    }

    @Test fun stabilizationMarginNeverPumps() {
        val s = HorizonState()
        val crop = s.fixedCropScale()
        (0..36).forEach { i ->
            s.updateRoll(i * 10f)
            assertEquals(crop, s.fixedCropScale(), 0.0001f)
        }
        assertTrue(crop > 2f)
        s.toggleEnabled()
        assertEquals(1f, s.fixedCropScale(), 0.0001f)
    }

    @Test fun correctionWorksRegardlessOfHudOrientation() {
        val s = HorizonState()
        s.updateRoll(-90f)
        assertEquals(180f, s.correctionDegrees(), 0.01f)
        s.updateRoll(45f)
        assertEquals(45f, s.correctionDegrees(), 0.01f)
        s.startRecording()
        s.updateRoll(0f)
        assertEquals(90f, s.correctionDegrees(), 0.01f)
        s.stopRecording()
        assertEquals(90f, s.correctionDegrees(), 0.01f)
    }
}
