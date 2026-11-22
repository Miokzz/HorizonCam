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

    @Test fun stillUsesPublicIndependentSensorBeforeAnAdvertisedPhysicalMember() {
        val independent = LensZoomSpec("rear-wide", 0.62f, 1f, 4f, standaloneSensor = true)
        val physical = LensZoomSpec("wide-member", 0.60f, 1f, 1f, fixedPhysicalStream = true)
        val inventory = listOf(main, physical, independent)
        assertEquals("rear-wide", ZoomRouter.route(0.6f, "main", "main", inventory, false,
            stillPhoto = true).targetKey)
        val resumed = ZoomRouter.route(0.6f, "rear-wide", "main", inventory, false,
            rejected = setOf("rear-wide"), stillPhoto = true)
        assertEquals("wide-member", resumed.targetKey)
        assertEquals(0.6f, resumed.displayZoom, 0.001f)
    }

    @Test fun thirtyTimesUsesTeleRelativeToMainAndHardwareBeforeResidualCrop() {
        val independent = LensZoomSpec("rear-tele", 2.9f, 1f, 4f, standaloneSensor = true)
        val physical = LensZoomSpec("tele-member", 3f, 1f, 1f, fixedPhysicalStream = true)
        val inventory = listOf(main, independent, physical)
        val atThree = ZoomRouter.route(3f, "main", "main", inventory, false, stillPhoto = true)
        assertEquals("rear-tele", atThree.targetKey)
        assertEquals(3f / 2.9f, atThree.cameraZoomRatio, 0.001f)
        val atThirty = ZoomRouter.route(30f, "rear-tele", "main", inventory, false, stillPhoto = true)
        assertFalse(atThirty.requiresRebind)
        assertEquals(4f, atThirty.cameraZoomRatio, 0.001f)
        assertEquals(30f / (4f * 2.9f), StillFraming.digitalRatio(30f, atThirty.displayZoom), 0.001f)
    }

    @Test fun unverifiedLogicalAliasCannotDisplaceDefaultLogicalZoom() {
        val alias = LensZoomSpec("alternative-logical", 2.9f, 1f, 6f)
        val result = ZoomRouter.route(3f, "main", "main", listOf(main, alias), false, stillPhoto = true)
        assertEquals("main", result.targetKey)
        assertEquals(3f, result.cameraZoomRatio, 0.001f)
    }
}
