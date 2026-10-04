package com.miokzz.horizoncam

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * Fullscreen center-crop viewfinder for the already GPU-stabilized output.
 * It NEVER applies an extra sensor/display rotation. Aspect correction is
 * presentation-only, so the recorded frame is untouched.
 */
class HorizonPreviewTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), Preview.SurfaceProvider, TextureView.SurfaceTextureListener {

    private data class Active(val request: SurfaceRequest, val surface: Surface, val texture: SurfaceTexture)
    private val main = ContextCompat.getMainExecutor(context)
    private var waiting: SurfaceRequest? = null
    private var active: Active? = null
    private var destroyLater: SurfaceTexture? = null
    private var bufferWidth = 1920
    private var bufferHeight = 1080

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    override fun onSurfaceRequested(request: SurfaceRequest) {
        waiting?.willNotProvideSurface()
        waiting = request
        bufferWidth = request.resolution.width
        bufferHeight = request.resolution.height
        request.addRequestCancellationListener(main) {
            if (waiting === request) waiting = null
        }
        tryProvide()
        updateTransformForLayout()
    }

    private fun tryProvide() {
        if (active != null) return
        val request = waiting ?: return
        val texture = surfaceTexture ?: return
        if (!isAvailable) return
        texture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(texture)
        val current = Active(request, surface, texture)
        active = current
        waiting = null
        updateTransformForLayout()
        request.provideSurface(surface, main) {
            surface.release()
            if (active === current) active = null
            if (destroyLater === texture) {
                texture.release()
                destroyLater = null
            }
            tryProvide()
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        tryProvide()
        updateTransformForLayout()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        updateTransformForLayout()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        waiting?.willNotProvideSurface()
        waiting = null
        return if (active?.texture === texture) {
            destroyLater = texture
            false
        } else true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    override fun performClick(): Boolean = super.performClick()

    /**
     * A landscape video cannot fill a portrait display without cropping.
     * Center crop is a presentation choice. There are no black letterboxes,
     * texture stretching, UI rotation hacks or field-of-view "breathing".
     */
    private fun updateTransformForLayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val srcAspect = (bufferWidth.toFloat() / bufferHeight.coerceAtLeast(1))
        val dstAspect = w / h
        val sx: Float
        val sy: Float
        if (srcAspect >= dstAspect) {
            sx = srcAspect / dstAspect
            sy = 1f
        } else {
            sx = 1f
            sy = dstAspect / srcAspect
        }
        val matrix = Matrix()
        matrix.setScale(sx, sy, w / 2f, h / 2f)
        setTransform(matrix)
    }
}
