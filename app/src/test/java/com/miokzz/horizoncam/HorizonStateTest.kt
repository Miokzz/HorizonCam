package com.miokzz.horizoncam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizonStateTest {
    @Test fun recordingFreezesOutputNotSensor() {
        val state = HorizonState()
        state.updateRoll(90f)
        state.startRecording()
        state.updateRoll(180f)
        assertEquals(-90f, state.correctionDegrees(), 0.01f)
        state.updateRoll(-90f)
        assertEquals(180f, state.correctionDegrees(), 0.01f)
        state.stopRecording()
        // After REC ends the horizon stays where it was until UI orientation changes.
        assertEquals(180f, state.correctionDegrees(), 0.01f)
        state.alignToScreenOrientation()
        assertEquals(0f, state.correctionDegrees(), 0.01f)
    }

    @Test fun noSnapAtArbitrary55DegreeThreshold() {
        val state = HorizonState()
        state.updateRoll(0f)
        state.updateRoll(30f)
        assertEquals(-30f, state.correctionDegrees(), 0.01f)
        state.updateRoll(56f)
        assertEquals(-56f, state.correctionDegrees(), 0.01f)
        state.updateRoll(89f)
        assertEquals(-89f, state.correctionDegrees(), 0.01f)
        // Real screen rotation updates the target orientation.
        state.alignToScreenOrientation()
        assertEquals(1f, state.correctionDegrees(), 0.01f)
    }

    @Test fun captureDoesNotReorientMidTake() {
        val state = HorizonState()
        state.updateRoll(-90f)
        state.alignToScreenOrientation()
        state.startRecording()
        state.updateRoll(30f)
        state.alignToScreenOrientation()
        assertEquals(-120f, state.correctionDegrees(), 0.01f)
        state.updateRoll(90f)
        assertEquals(-180f, state.correctionDegrees(), 0.01f)
    }

    @Test fun cropIsFixedForEveryRoll() {
        val state = HorizonState()
        state.updateRoll(90f)
        state.startRecording()
        val zoom = state.fixedCropScale()
        state.updateRoll(-90f)
        assertEquals(zoom, state.fixedCropScale(), 0.0001f)
        assertTrue(zoom > 2f)
        state.toggleEnabled()
        assertEquals(1f, state.fixedCropScale(), 0.0001f)
    }
}
