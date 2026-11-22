package com.miokzz.horizoncam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoZoomTransitionTest {
    @Test fun reversalAndFurtherDragCannotReplayAnEarlierZoom() {
        val state = PhotoZoomTransition()
        state.beginSession(1)
        state.request(3f, null, false, 0)
        val tele = state.intent.generation
        state.beginSession(2)
        state.request(2.4f, null, true, 40)
        val reversal = state.intent.generation
        state.request(7.5f, null, true, 90)
        val latest = state.intent.generation

        assertFalse(state.confirm(2, tele))
        assertFalse(state.confirm(2, reversal))
        assertFalse(state.finishGesture(reversal))
        assertTrue(state.isPending)
        assertEquals(7.5f, state.intent.total, 0f)
        assertTrue(state.finishGesture(latest))
        assertTrue(state.confirm(2, latest))
        assertFalse(state.isPending)
    }

    @Test fun staleSessionCannotEnableCaptureEvenForTheLatestIntent() {
        val state = PhotoZoomTransition()
        state.beginSession(1)
        state.request(30f, null, false, 0)
        val generation = state.intent.generation
        state.beginSession(2)
        assertFalse(state.confirm(1, generation))
        assertTrue(state.isPending)
        assertTrue(state.confirm(2, generation))
        assertFalse(state.isPending)
        state.invalidate()
        assertTrue(state.isPending)
    }

    @Test fun opticalQuietIntervalTracksTheLatestGestureAndPreset() {
        val state = PhotoZoomTransition()
        state.request(3f, null, true, 100)
        state.request(30f, null, true, 160)
        assertEquals(270L, state.intent.switchNotBefore)
        val drag = state.intent.generation
        state.request(0.6f, null, false, 170)
        assertFalse(state.finishGesture(drag))
        assertEquals(0.6f, state.intent.total, 0f)
        assertEquals(0L, state.intent.switchNotBefore)
    }
}
