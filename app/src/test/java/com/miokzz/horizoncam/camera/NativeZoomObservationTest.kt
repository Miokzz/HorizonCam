package com.miokzz.horizoncam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeZoomObservationTest {
    @Test fun corroboratesZoomOutAndDoesNotDoubleApplyCropToZoomRatio() {
        assertEquals(0.6f, NativeZoomObservation(0.6f, 1f, 0.6f, 1f).appliedFor(0.6f))
        assertEquals(3f, NativeZoomObservation(3f, 1f, 3f, 1.15f).appliedFor(3f))
    }

    @Test fun unchangedHalZoomIsARealObservationAndMissingZoomIsUnknown() {
        assertEquals(1f, NativeZoomObservation(3f, 1f, 1f, 1f).appliedFor(3f))
        assertNull(NativeZoomObservation(3f, 1f, null, 1f).appliedFor(3f))
        assertNull(NativeZoomObservation(1f, 1f, 1f, 1f).appliedFor(3f))
    }

    @Test fun usesScalerCropForLegacyAndCropOnlyRequests() {
        assertEquals(2f, NativeZoomObservation(null, 2f, null, 2f).appliedFor(2f))
        assertEquals(2.01f, NativeZoomObservation(1f, 2f, 1f, 2.01f).appliedFor(2f))
        assertNull(NativeZoomObservation(1f, 2f, 1f, 2f).appliedFor(1f))
        assertNull(NativeZoomObservation(null, null, null, null).appliedFor(1f))
    }
}
