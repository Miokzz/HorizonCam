package com.miokzz.horizoncam.camera

/** Keeps an old camera/session/result from ever being displayed as fresh evidence. */
class CaptureEvidence(private val nowMillis: () -> Long, private val maxAgeMillis: Long = 1500L) {
    private var generation = 0L
    private var lastFrame = -1L
    private var physicalId: String? = null
    private var receivedAt: Long? = null
    private var sessionIdentity: Any? = null

    @Synchronized fun reset(): Long {
        generation++
        lastFrame = -1L
        physicalId = null
        receivedAt = null
        sessionIdentity = null
        return generation
    }

    @Synchronized fun beginSession(token: Long, session: Any): Boolean {
        if (token != generation || sessionIdentity === session) return false
        sessionIdentity = session
        lastFrame = -1L
        physicalId = null
        receivedAt = null
        return true
    }

    @Synchronized fun record(token: Long, frame: Long, reportedPhysicalId: String?, session: Any? = null): Boolean {
        if (token != generation || sessionIdentity !== session || frame <= lastFrame) return false
        lastFrame = frame
        // Null on a new result MUST clear the previous ID.
        physicalId = reportedPhysicalId?.takeIf { it.isNotBlank() }
        receivedAt = nowMillis()
        return true
    }

    @Synchronized fun clear(token: Long, session: Any? = null): Boolean {
        if (token != generation || sessionIdentity !== session) return false
        physicalId = null
        receivedAt = null
        return true
    }

    @Synchronized fun endSession(token: Long, session: Any): Boolean {
        if (token != generation || sessionIdentity !== session) return false
        sessionIdentity = null
        physicalId = null
        receivedAt = null
        lastFrame = -1L
        return true
    }

    @Synchronized fun activePhysicalId(): String? {
        val age = receivedAt?.let { nowMillis() - it } ?: return null
        return physicalId.takeIf { age in 0..maxAgeMillis }
    }
}
