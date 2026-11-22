package com.miokzz.horizoncam.camera

/**
 * The latest user intent is independent of the bound session and its applied zoom.
 * A gesture ending or a camera callback may confirm an intent but cannot replay it.
 */
internal class PhotoZoomTransition {
    data class Intent(
        val generation: Long,
        val total: Float,
        val preferred: String?,
        val continuous: Boolean,
        val switchNotBefore: Long
    )

    var intent = Intent(0, 1f, null, false, 0L)
        private set
    private var session = 0
    private var appliedGeneration: Long? = null
    val isPending: Boolean get() = appliedGeneration != intent.generation

    fun beginSession(session: Int) {
        this.session = session
        invalidate()
    }

    fun request(total: Float, preferred: String?, continuous: Boolean, now: Long) {
        val current = intent
        if (total == current.total && preferred == current.preferred && continuous == current.continuous) return
        val generation = if (total == current.total && preferred == current.preferred) current.generation
            else current.generation + 1
        intent = Intent(generation, total, preferred, continuous, if (continuous) now + OPTICAL_SETTLE_MS else 0L)
    }

    fun finishGesture(generation: Long?): Boolean {
        if (generation != intent.generation || !intent.continuous) return false
        intent = intent.copy(continuous = false, switchNotBefore = 0L)
        return true
    }

    fun confirm(session: Int, generation: Long): Boolean {
        if (session != this.session || generation != intent.generation) return false
        appliedGeneration = generation
        return true
    }

    fun invalidate() { appliedGeneration = null }

    private companion object {
        const val OPTICAL_SETTLE_MS = 110L
    }
}
