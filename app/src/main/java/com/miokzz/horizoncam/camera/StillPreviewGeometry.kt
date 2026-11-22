package com.miokzz.horizoncam.camera

/** A software crop is centered in the same 4:3 CameraX viewport as its JPEG source. */
object StillPreviewGeometry {
    fun confirmed(total: Float, opticalScale: Float, hardwareRatio: Float): Pair<Float, Float> {
        require(total.isFinite() && total > 0f && opticalScale.isFinite() && opticalScale > 0f)
        require(hardwareRatio.isFinite() && hardwareRatio > 0f)
        val sensor = opticalScale * hardwareRatio
        val residual = (total / sensor).coerceAtLeast(1f)
        return sensor * residual to residual
    }

    /** Dispatch to a scaled Android view already supplies inverse-transformed local coordinates. */
    fun localToVisible(coordinate: Float, extent: Float, residual: Float): Float =
        extent / 2f + (coordinate - extent / 2f) * residual

    fun visibleToLocal(coordinate: Float, extent: Float, residual: Float): Float =
        extent / 2f + (coordinate - extent / 2f) / residual

    fun meteringSize(residual: Float, precise: Boolean = false): Float {
        val visibleSize = if (precise) 0.08f else 0.15f
        return (visibleSize / residual.coerceAtLeast(1f)).coerceAtLeast(0.005f)
    }

    fun retryDelay(attempt: Int): Long? = listOf(450L, 1000L, 1800L).getOrNull(attempt)
}
