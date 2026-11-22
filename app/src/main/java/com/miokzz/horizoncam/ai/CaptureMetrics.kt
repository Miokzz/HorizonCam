package com.miokzz.horizoncam.ai

import android.graphics.Bitmap

/** Sample the whole framing at a bounded cost. These signals are diagnostic, not ground truth. */
object CaptureMetrics {
    fun summary(bitmap: Bitmap): SceneMetrics {
        val factor = minOf(1.0, 256.0 / maxOf(bitmap.width, bitmap.height))
        val w = (bitmap.width * factor).toInt().coerceAtLeast(1)
        val h = (bitmap.height * factor).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        try {
            val pixels = IntArray(w * h)
            small.getPixels(pixels, 0, w, 0, 0, w, h)
            return AiColor.scene(pixels, w, h)
        } finally { if (small !== bitmap) small.recycle() }
    }
}
