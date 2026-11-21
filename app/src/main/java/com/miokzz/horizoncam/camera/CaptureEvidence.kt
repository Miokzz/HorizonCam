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

    @Synchronized fun beginSession(token: Long, session: Any) {
        if (token != generation) return
        sessionIdentity = session
        lastFrame = -1L
        physicalId = null
        receivedAt = null
    }

    @Synchronized fun record(token: Long, frame: Long, reportedPhysicalId: String?, session: Any? = null) {
        if (token != generation || sessionIdentity !== session || frame <= lastFrame) return
        lastFrame = frame
        // Null on a new result MUST clear the previous ID.
        physicalId = reportedPhysicalId?.takeIf { it.isNotBlank() }
        receivedAt = nowMillis()
    }

    @Synchronized fun clear(token: Long, session: Any? = null) {
        if (token != generation || sessionIdentity !== session) return
        physicalId = null
        receivedAt = null
    }

    @Synchronized fun endSession(token: Long, session: Any) {
        if (token != generation || sessionIdentity !== session) return
        sessionIdentity = null
        physicalId = null
        receivedAt = null
        lastFrame = -1L
    }

    @Synchronized fun activePhysicalId(): String? {
        val age = receivedAt?.let { nowMillis() - it } ?: return null
        return physicalId.takeIf { age in 0..maxAgeMillis }
    }
}
