package com.miokzz.horizoncam.camera

import org.junit.Assert.*
import org.junit.Test

class ZoomRouterTest {
    private val main = LensZoomSpec("main", 1f, 1f, 8f)
    private val wide = LensZoomSpec("wide", 0.6f, 1f, 4f)
    private val tele = LensZoomSpec("tele", 3f, 1f, 5f)
    private val options = listOf(main, wide, tele)

    @Test fun logicalContinuousZoomTakesPriorityOverDedicatedTele() {
        val result = ZoomRouter.route(3f, "main", "main", options, false)
        assertEquals("main", result.targetKey)
        assertFalse(result.requiresRebind)
        assertEquals(3f, result.cameraZoomRatio, 0.001f)
    }

    @Test fun logicalUltrawideRangeAvoidsRebinding() {
        val continuous = options.map { if (it.key == "main") it.copy(minCameraZoom = 0.5f) else it }
        val result = ZoomRouter.route(0.6f, "main", "main", continuous, false)
        assertEquals("main", result.targetKey)
        assertFalse(result.requiresRebind)
        assertTrue(result.requestedAvailable)
    }

    @Test fun intermediatePinchOnStandaloneUltrawideKeepsItsSessionAndUsesRealRatio() {
        val result = ZoomRouter.route(0.8f, "wide", "main", options, false)
        assertEquals("wide", result.targetKey)
        assertFalse(result.requiresRebind)
        assertEquals(0.8f / 0.6f, result.cameraZoomRatio, 0.001f)
        assertEquals(0.8f, result.displayZoom, 0.001f)
    }

    @Test fun ultrawideBelowLogicalRangeRebindsOnlyBeforeRecording() {
        assertTrue(ZoomRouter.route(0.6f, "main", "main", options, false).requiresRebind)
        val recording = ZoomRouter.route(0.6f, "main", "main", options, true)
        assertEquals("main", recording.targetKey)
        assertFalse(recording.requiresRebind)
        assertTrue(recording.limited)
        assertEquals(1f, recording.displayZoom, 0f)
    }

    @Test fun recordingIgnoresExplicitLensSwitchAndClampsToCurrentRange() {
        val result = ZoomRouter.route(12f, "wide", "main", options, true, preferredKey = "tele")
        assertEquals("wide", result.targetKey)
        assertFalse(result.requiresRebind)
        assertEquals(2.4f, result.displayZoom, 0.001f)
        assertTrue(result.limited)
    }

    @Test fun explicitTeleCanBeSelectedWhileLogicalZoomCouldCoverRequestedValue() {
        val result = ZoomRouter.route(3f, "main", "main", options, false, preferredKey = "tele")
        assertEquals("tele", result.targetKey)
        assertEquals(1f, result.cameraZoomRatio, 0f)
        assertTrue(result.requiresRebind)
    }

    @Test fun rejectedLensFallsBackToLogicalAndReportsUnavailableRequest() {
        val result = ZoomRouter.route(0.6f, "wide", "main", options, false, setOf("wide"))
        assertEquals("main", result.targetKey)
        assertEquals(1f, result.displayZoom, 0f)
        assertTrue(result.limited)
    }

    @Test fun rejectedLensIsNotSelectedAgainByPreset() {
        val result = ZoomRouter.route(0.6f, "main", "main", options, false, setOf("wide"))
        assertEquals("main", result.targetKey)
        assertFalse(result.requiresRebind)
        assertFalse(result.requestedAvailable)
    }

    @Test fun physicalStreamDoesNotInventDigitalZoomRange() {
        val physical = LensZoomSpec("physical", 0.56f, 1f, 1f, true)
        val result = ZoomRouter.route(0.8f, "physical", "main", listOf(main, physical), true)
        assertEquals(1f, result.cameraZoomRatio, 0f)
        assertEquals(0.56f, result.displayZoom, 0f)
        assertTrue(result.limited)
        assertFalse(result.requiresRebind)
    }

    @Test fun unavailableTelePresetClampsInsteadOfClaimingOpticalTele() {
        val result = ZoomRouter.route(3f, "main", "main", listOf(main.copy(maxCameraZoom = 2f)), false)
        assertEquals(2f, result.displayZoom, 0f)
        assertTrue(result.limited)
    }

    @Test fun invalidGestureCannotGenerateNaNZoom() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f)) {
            val result = ZoomRouter.route(invalid, "main", "main", options, false)
            assertEquals(1f, result.displayZoom, 0f)
        }
    }
}
