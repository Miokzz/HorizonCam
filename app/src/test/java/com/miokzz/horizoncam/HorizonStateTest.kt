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
        assertEquals(0f, state.correctionDegrees(), 0.01f)
    }

    @Test fun autoOrientationBeforeRecordingNeedsNoTap() {
        val state = HorizonState()
        state.updateRoll(0f)
        state.updateRoll(30f)
        assertEquals(-30f, state.correctionDegrees(), 0.01f)
        state.updateRoll(90f)
        assertEquals(0f, state.correctionDegrees(), 0.01f)
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
