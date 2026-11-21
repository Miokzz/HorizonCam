package com.miokzz.horizoncam.ai

object RestorationStrength {
    fun forCapture(zoom: Float, iso: Int): Float {
        val magnification = ((zoom.coerceAtLeast(1f) - 1f) / 9f).coerceIn(0f, 1f)
        val noise = ((iso.coerceAtLeast(100) - 100) / 1500f).coerceIn(0f, 1f)
        return 0.24f + 0.50f * magnification + 0.12f * noise
    }
}
