package com.miokzz.horizoncam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizonStateTest {
    @Test fun landscapeGravityReferenceIsNeverManuallyReset() {
        val state = HorizonState()
        state.pushRoll(1_000_000_000L, 90f)
        assertEquals(0f, state.correctionDegrees(), 0.01f)
        state.startRecording()
        state.pushRoll(1_020_000_000L, -90f)
        assertEquals(-180f, state.correctionDegrees(), 0.01f)
        state.stopRecording()
        assertEquals(-180f, state.correctionDegrees(), 0.01f)
    }

    @Test fun full360DegreesIsContinuousOnBothSidesOfWrap() {
        val state = HorizonState()
        val angles = listOf(90f, 135f, 179f, -179f, -135f, -90f, -45f, 0f, 45f, 90f)
        angles.forEachIndexed { i, angle ->
            state.pushRoll(1_000_000_000L + i * 10_000_000L, angle)
        }
        assertEquals(0f, state.correctionDegrees(), 0.01f)
    }

    @Test fun modeSelectionChangesRealGeometry() {
        val state = HorizonState()
        state.pushRoll(1_000_000_000L, 120f)
        state.setMode(StabilizationMode.OFF)
        assertEquals(0f, state.frameGeometry(1_000_000_000L).rotationDegrees, 0f)
        assertEquals(1f, state.fixedCropScale(), 0f)
        state.setMode(StabilizationMode.STEADY)
        assertEquals(0f, state.correctionDegrees(), 0f)
        state.setMode(StabilizationMode.HORIZONTAL_LOCK)
        assertEquals(-30f, state.correctionDegrees(), 0.01f)
        assertTrue(state.fixedCropScale() > 2f)
    }
}
