package com.miokzz.horizoncam.camera

import kotlin.math.abs

/** Relative zoom is an approximate field-of-view scale, never evidence of optical switching. */
data class LensZoomSpec(
    val key: String,
    val relativeZoom: Float,
    val minCameraZoom: Float,
    val maxCameraZoom: Float,
    val fixedPhysicalStream: Boolean = false
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
        // For a still, prefer a physically addressable longer focal length which can contain
        // the complete requested field. The logical camera remains the fallback on bind failure.
        val longLens = if (stillPhoto && !recording) usable.filter {
            it.relativeZoom >= 1.5f && it.relativeZoom <= safeRequested && it.key != defaultKey
        }.maxWithOrNull(compareBy<LensZoomSpec> { it.relativeZoom }.thenBy { !it.fixedPhysicalStream }) else null
        val target = when {
            recording -> current // A recording must never trigger unbind/rebind.
            explicit != null -> explicit
            longLens != null -> longLens
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
