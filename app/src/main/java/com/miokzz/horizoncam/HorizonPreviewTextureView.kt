package com.miokzz.horizoncam

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * Viewfinder for a fixed 16:9 landscape GPU output.
 *
 * SurfaceOutput.updateTransformMatrix() already performs CameraX's camera
 * rotation/crop in OpenGL. This TextureView must NOT rotate that image a second
 * time or react to changes in Android's UI orientation.
 */
class HorizonPreviewTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), Preview.SurfaceProvider, TextureView.SurfaceTextureListener {
    private data class Active(val request: SurfaceRequest, val surface: Surface, val texture: SurfaceTexture)
    private val main = ContextCompat.getMainExecutor(context)
    private var waiting: SurfaceRequest? = null
    private var active: Active? = null
    private var releaseWhenFree: SurfaceTexture? = null
    private var viewfinderAspect = 16f / 9f

    init {
        surfaceTextureListener = this
        isOpaque = true
        setBackgroundColor(Color.BLACK)
    }

    override fun onSurfaceRequested(request: SurfaceRequest) {
        waiting?.willNotProvideSurface()
        waiting = request
        request.addRequestCancellationListener(main) {
            if (waiting === request) waiting = null
        }
        // Keep a landscape canvas even if the activity is currently portrait.
        // No setTransformationInfoListener: GPU applies the camera transform.
        drawFittedLandscape()
        tryProvide()
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
        drawFittedLandscape()
        request.provideSurface(surface, main) {
            surface.release()
            if (active === current) active = null
            if (releaseWhenFree === texture) {
                texture.release()
                releaseWhenFree = null
            }
            tryProvide()
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        drawFittedLandscape()
        tryProvide()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        drawFittedLandscape()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        waiting?.willNotProvideSurface()
        waiting = null
        return if (active?.texture === texture) {
            releaseWhenFree = texture
            false
        } else true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    private fun drawFittedLandscape() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        // Surface texture normally fills the entire view, regardless of source
        // aspect ratio. In portrait this creates false portrait framing.
        // Compress it into a centered 16:9 landscape viewport, without
        // stretching or re-rotating CameraX's already corrected GPU image.
        val contentW = min(vw, vh * viewfinderAspect)
        val contentH = contentW / viewfinderAspect
        val transform = Matrix().apply {
            setScale(contentW / vw, contentH / vh, vw / 2f, vh / 2f)
        }
        setTransform(transform)
    }
}
