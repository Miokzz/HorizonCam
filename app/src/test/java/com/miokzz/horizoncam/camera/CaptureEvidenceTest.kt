package com.miokzz.horizoncam.camera

import org.junit.Assert.*
import org.junit.Test

class CaptureEvidenceTest {
    private var now = 0L
    private val evidence = CaptureEvidence({ now })

    @Test fun sessionSwitchClearsIdAndRejectsOldCallbacks() {
        val first = evidence.reset()
        evidence.record(first, 99, "ultra")
        assertEquals("ultra", evidence.activePhysicalId())
        val second = evidence.reset()
        assertNull(evidence.activePhysicalId())
        evidence.record(first, 100, "ultra")
        assertNull(evidence.activePhysicalId())
        evidence.record(second, 1, "main")
        evidence.clear(first)
        assertEquals("main", evidence.activePhysicalId())
    }

    @Test fun nullResultClearsPreviousIdRatherThanRetainingIt() {
        val token = evidence.reset()
        evidence.record(token, 1, "tele")
        evidence.record(token, 2, null)
        assertNull(evidence.activePhysicalId())
    }

    @Test fun expiredEvidenceIsUnknownEvenIfCameraStopsProducingCallbacks() {
        val token = evidence.reset()
        evidence.record(token, 1, "main")
        now = 1501L
        assertNull(evidence.activePhysicalId())
    }

    @Test fun lateOlderFrameCannotReplaceNewResult() {
        val token = evidence.reset()
        evidence.record(token, 4, "tele")
        evidence.record(token, 3, "main")
        assertEquals("tele", evidence.activePhysicalId())
        evidence.clear(token)
        assertNull(evidence.activePhysicalId())
    }

    @Test fun blankIdIsNotEvidence() {
        val token = evidence.reset()
        evidence.record(token, 1, " ")
        assertNull(evidence.activePhysicalId())
    }

    @Test fun firstRealCaptureSessionBindsWithoutConfigurationCallback() {
        val token = evidence.reset()
        val session = Any()
        assertTrue(evidence.beginSession(token, session, 500L))
        assertTrue(evidence.record(token, 1, "tele", session, 500L))
        assertEquals("tele", evidence.activePhysicalId())
    }

    @Test fun aNewerRealSessionAcceptsResetFrameNumbersAndIgnoresOldCallbacks() {
        val token = evidence.reset()
        val firstSession = Any()
        val secondSession = Any()
        evidence.beginSession(token, firstSession, 500L)
        evidence.record(token, 1000, "main", firstSession, 600L)
        assertFalse(evidence.beginSession(token, secondSession, 510L))
        assertTrue(evidence.beginSession(token, secondSession, 700L))
        assertNull(evidence.activePhysicalId())
        evidence.record(token, 1, "ultra", secondSession, 700L)
        assertFalse(evidence.clear(token, firstSession))
        assertFalse(evidence.beginSession(token, firstSession, 800L))
        evidence.record(token, 1001, "main", firstSession)
        assertEquals("ultra", evidence.activePhysicalId())
        evidence.reset()
        evidence.record(token, 2, "ultra", secondSession)
        assertNull(evidence.activePhysicalId())
    }
}
