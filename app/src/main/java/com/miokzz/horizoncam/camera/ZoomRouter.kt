package com.miokzz.horizoncam.camera

import kotlin.math.abs

/** Relative zoom is an approximate field-of-view scale, never evidence of optical switching. */
data class LensZoomSpec(
    val key: String,
    val relativeZoom: Float,
    val minCameraZoom: Float,
    val maxCameraZoom: Float,
    val fixedPhysicalStream: Boolean = false,
    val standaloneSensor: Boolean = false
) {
    init {
        require(relativeZoom.isFinite() && relativeZoom > 0f)
        require(minCameraZoom.isFinite() && minCameraZoom > 0f)
        require(maxCameraZoom.isFinite() && maxCameraZoom >= minCameraZoom)
    }
    val minDisplayZoom: Float get() = relativeZoom * minCameraZoom
    val maxDisplayZoom: Float get() = relativeZoom * maxCameraZoom
}

data class ZoomRoute(
    val targetKey: String,
    val cameraZoomRatio: Float,
    val displayZoom: Float,
    val requiresRebind: Boolean,
    val limited: Boolean
) {
    val requestedAvailable: Boolean get() = !limited
}

/** Pure routing policy shared by presets, pinch and explicit lens selection. */
object ZoomRouter {
    private const val EPSILON = 0.001f

    fun route(
        requested: Float,
        currentKey: String,
        defaultKey: String,
        options: List<LensZoomSpec>,
        recording: Boolean,
        rejected: Set<String> = emptySet(),
        preferredKey: String? = null,
        stillPhoto: Boolean = false
    ): ZoomRoute {
        require(options.any { it.key == defaultKey })
        val safeRequested = requested.takeIf { it.isFinite() && it > 0f } ?: 1f
        val default = options.first { it.key == defaultKey }
        val current = options.firstOrNull { it.key == currentKey } ?: default
        val usable = options.filter { it.key !in rejected || it.key == defaultKey }
        fun contains(spec: LensZoomSpec) = safeRequested >= spec.minDisplayZoom - EPSILON &&
            safeRequested <= spec.maxDisplayZoom + EPSILON

        val explicit = usable.firstOrNull { it.key == preferredKey }
        // Select the sensor band using the requested total framing. A calibrated tele FOV
        // close to 3x must still be attempted at the nominal 3x boundary.
        val band = when { safeRequested < 1f -> 0; safeRequested < 3f -> 1; else -> 2 }
        val dedicated = if (stillPhoto && !recording) usable.filter { candidate ->
            candidate.key != defaultKey && (candidate.standaloneSensor || candidate.fixedPhysicalStream) && when (band) {
                0 -> candidate.relativeZoom in 0.35f..0.85f && candidate.relativeZoom <= safeRequested + 0.06f
                1 -> candidate.relativeZoom in 0.85f..1.2f
                else -> candidate.relativeZoom in 2.2f..4.2f && candidate.relativeZoom <= safeRequested + 0.08f
            }
        }.minWithOrNull(compareBy<LensZoomSpec> { !it.standaloneSensor }
            .thenBy { abs(it.relativeZoom - when (band) { 0 -> 0.6f; 1 -> 1f; else -> 3f }) }) else null
        val target = when {
            recording -> current // A recording must never trigger unbind/rebind.
            explicit != null -> explicit
            dedicated != null -> dedicated
            current.key in rejected -> default
            default.key !in rejected && contains(default) -> default // Preserve logical continuity.
            current.key !in rejected && contains(current) -> current
            else -> usable.filter(::contains).minByOrNull { abs(it.relativeZoom - safeRequested) }
                ?: usable.minBy { spec ->
                    abs(safeRequested - safeRequested.coerceIn(spec.minDisplayZoom, spec.maxDisplayZoom))
                }
        }
        val cameraZoom = (safeRequested / target.relativeZoom)
            .coerceIn(target.minCameraZoom, target.maxCameraZoom)
        val display = cameraZoom * target.relativeZoom
        return ZoomRoute(target.key, cameraZoom, display, !recording && target.key != currentKey,
            abs(display - safeRequested) > EPSILON)
    }
}
