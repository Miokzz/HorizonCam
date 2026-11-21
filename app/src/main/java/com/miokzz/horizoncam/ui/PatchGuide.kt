package com.miokzz.horizoncam.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.widget.ImageView

/** Shows the exact native-pixel crop on the downscaled, upright source preview. */
class PatchGuide(private val image: ImageView) : Drawable() {
    var centerX = 0.5f
    var centerY = 0.5f
    var sourceLongEdge = 0
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun draw(canvas: Canvas) {
        val source = image.drawable ?: return
        if (sourceLongEdge <= 0 || source.intrinsicWidth <= 0 || source.intrinsicHeight <= 0) return
        val rect = RectF(0f, 0f, source.intrinsicWidth.toFloat(), source.intrinsicHeight.toFloat())
        image.imageMatrix.mapRect(rect)
        val edge = minOf(rect.width(), rect.height(), 256f * maxOf(rect.width(), rect.height()) / sourceLongEdge)
        val left = (rect.left + rect.width() * centerX - edge / 2).coerceIn(rect.left, maxOf(rect.left, rect.right - edge))
        val top = (rect.top + rect.height() * centerY - edge / 2).coerceIn(rect.top, maxOf(rect.top, rect.bottom - edge))
        val box = RectF(left, top, left + edge, top + edge)
        val density = image.resources.displayMetrics.density
        line.color = 0xA0000000.toInt()
        line.strokeWidth = 3f * density
        canvas.drawRect(box, line)
        line.color = 0xFFFFD84A.toInt()
        line.strokeWidth = 1.3f * density
        canvas.drawRect(box, line)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
