package com.miokzz.horizoncam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class CameraGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        strokeWidth = context.resources.displayMetrics.density * 0.8f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (i in 1..2) {
            val x = width * i / 3f
            val y = height * i / 3f
            canvas.drawLine(x, 0f, x, height.toFloat(), pen)
            canvas.drawLine(0f, y, width.toFloat(), y, pen)
        }
    }
}
