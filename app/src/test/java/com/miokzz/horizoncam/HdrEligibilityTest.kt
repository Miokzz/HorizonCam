package com.miokzz.horizoncam

import com.miokzz.horizoncam.photo.HdrEligibility
import org.junit.Assert.*
import org.junit.Test

class HdrEligibilityTest {
    @Test fun requestAloneIsNotHdrCapability() {
        val state = HdrEligibility()
        assertFalse(state.decide("main", requested = true, physical = false).enabled)
        assertNotNull(state.decide("main", requested = true, physical = false).reason)
    }

    @Test fun supportedLogicalCameraEnablesOnlyOnExplicitRequest() {
        val state = HdrEligibility()
        state.record("main", true, null)
        assertTrue(state.decide("main", requested = true, physical = false).enabled)
        assertFalse(state.decide("main", requested = false, physical = false).enabled)
    }

    @Test fun supportForAnotherLensDoesNotLeakToSelectedLens() {
        val state = HdrEligibility()
        state.record("main", true, null)
        state.record("wide", false, "OEM does not support HDR")
        assertFalse(state.decide("wide", requested = true, physical = false).enabled)
        assertFalse(state.decide("tele", requested = true, physical = false).enabled)
    }

    @Test fun physicalSelectorCannotBorrowLogicalHdrSupport() {
        val state = HdrEligibility()
        state.record("main", true, null)
        assertFalse(state.decide("main", requested = true, physical = true).enabled)
    }

    @Test fun latePositiveQueryCannotOverrideActualBindRejection() {
        val state = HdrEligibility()
        state.record("main", true, null)
        state.reject("main", "actual session combination failed")
        state.record("main", true, null)
        val result = state.decide("main", requested = true, physical = false)
        assertFalse(result.enabled)
        assertEquals("actual session combination failed", result.reason)
    }

    @Test fun rejectedCameraDoesNotDisableOtherSupportedCameras() {
        val state = HdrEligibility()
        state.reject("main", "main failed")
        state.record("wide", true, null)
        assertTrue(state.decide("wide", requested = true, physical = false).enabled)
    }
}
