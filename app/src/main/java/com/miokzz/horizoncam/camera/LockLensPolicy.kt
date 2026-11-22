package com.miokzz.horizoncam.camera

import kotlin.math.abs

/** Lens selection only. Recording never changes its bound camera or viewport. */
object LockLensPolicy {
    fun route(options: List<LensZoomSpec>, defaultKey: String, currentKey: String,
              rejected: Set<String>, explicit: Boolean = false, recording: Boolean = false): ZoomRoute {
        val current = options.firstOrNull { it.key == currentKey } ?: options.first { it.key == defaultKey }
        if (explicit || recording) return ZoomRouter.route(current.relativeZoom, current.key,defaultKey,options,
            recording = true, rejected = rejected)
        val wide = options.filter {
            (it.standaloneSensor || it.fixedPhysicalStream) && it.relativeZoom in 0.35f..0.85f && it.key !in rejected
        }.minWithOrNull(compareBy<LensZoomSpec> { !it.standaloneSensor }.thenBy { abs(it.relativeZoom - 0.6f) })
        return ZoomRouter.route(wide?.relativeZoom ?: 0.6f, current.key, defaultKey, options,
            recording = false, rejected = rejected, preferredKey = wide?.key ?: defaultKey)
    }
}
