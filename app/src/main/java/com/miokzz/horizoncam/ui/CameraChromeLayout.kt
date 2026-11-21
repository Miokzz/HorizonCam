package com.miokzz.horizoncam.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import com.miokzz.horizoncam.R
import kotlin.math.min

/** Reflows the same live views when configuration changes; never recreates a camera surface. */
class CameraChromeLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private var photoMode = false
    private var lockedVideo = false

    fun setLockedVideoMode(enabled: Boolean) {
        if (lockedVideo == enabled) return
        lockedVideo = enabled
        requestLayout()
    }

    fun setPhotoMode(enabled: Boolean) {
        if (photoMode == enabled) return
        photoMode = enabled
        requestLayout()
    }

    private data class Region(val left: Int, val top: Int, val width: Int, val height: Int)

    private fun regions(width: Int, height: Int): Map<Int, Region> {
        val w = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val h = (height - paddingTop - paddingBottom).coerceAtLeast(0)
        val landscape = w > h
        val top = min(dp(60), h / 5)
        val rail = min(dp(60), h / 5)
        val capture = min(dp(108), if (landscape) w / 4 else h / 4)
        val previewW = if (landscape) w - capture else w
        val previewH = (h - top - rail - if (landscape) 0 else capture).coerceAtLeast(0)
        var preview = Region(0, top, previewW, previewH)
        if (photoMode || lockedVideo) {
            val aspect = if (lockedVideo) 16f / 9f else if (landscape) 4f / 3f else 3f / 4f
            val frameW = min(previewW, (previewH * aspect).toInt())
            val frameH = min(previewH, (frameW / aspect).toInt())
            preview = Region((previewW - frameW) / 2, top + (previewH - frameH) / 2, frameW, frameH)
        }
        return mapOf(
            R.id.topControls to Region(0, 0, previewW, top),
            R.id.previewContainer to preview,
            R.id.captureControls to if (landscape) Region(previewW, 0, capture, h)
                else Region(0, top + previewH, w, capture),
            R.id.modeRail to Region(0, h - rail, previewW, rail)
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val slots = regions(width, height)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val slot = slots[child.id] ?: continue
            child.measure(MeasureSpec.makeMeasureSpec(slot.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(slot.height, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val slots = regions(right - left, bottom - top)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val slot = slots[child.id] ?: continue
            val x = paddingLeft + slot.left
            val y = paddingTop + slot.top
            child.layout(x, y, x + slot.width, y + slot.height)
        }
    }
}

/** Capture actions remain centered along the short edge, with 48dp minimum side actions. */
class CaptureControlsLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val vertical = height > width
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val fraction = when (child.id) {
                R.id.galleryThumbnail -> if (vertical) 0.80f else 0.18f
                R.id.pauseButton -> if (vertical) 0.20f else 0.82f
                else -> 0.5f
            }
            val x = (if (vertical) width / 2f else width * fraction).toInt() - child.measuredWidth / 2
            val y = (if (vertical) height * fraction else height / 2f).toInt() - child.measuredHeight / 2
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }
}
