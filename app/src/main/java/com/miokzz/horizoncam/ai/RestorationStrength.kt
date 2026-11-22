package com.miokzz.horizoncam.ai

object RestorationStrength {
    fun forCapture(zoom: Float, iso: Int): Float {
        val magnification = ((zoom.coerceAtLeast(1f) - 1f) / 9f).coerceIn(0f, 1f)
        val noise = ((iso.coerceAtLeast(100) - 100) / 1500f).coerceIn(0f, 1f)
        return 0.24f + 0.50f * magnification + 0.12f * noise
    }

    /** Bound unsupported changes where the source is flat, warm skin-like or has hard edges. */
    fun protect(measured: Int, predicted: Int, neighbour: Int, amount: Float): Int {
        val r = AiColor.red(measured)
        val g = AiColor.green(measured)
        val b = AiColor.blue(measured)
        val y = AiColor.luma(measured)
        val edge = kotlin.math.abs(y - AiColor.luma(neighbour))
        val skinLike = r > 45 && r >= g && g >= b && r - b in 12..110
        val limit = when {
            skinLike -> 8f
            edge >= 36f -> 12f
            edge < 4f -> 9f
            else -> 20f
        }
        val deltaY = (AiColor.luma(predicted) - y)
        val luminance = deltaY.coerceIn(-limit, limit) * amount
        // Restrict independent chroma changes; the prior must not recolor evidence.
        fun channel(original: Int, prediction: Int) = original + luminance +
            (prediction - original - deltaY).coerceIn(-5f, 5f) * amount
        return AiColor.pack(channel(r, AiColor.red(predicted)), channel(g, AiColor.green(predicted)),
            channel(b, AiColor.blue(predicted)))
    }
}
