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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Raw viewfinder. It displays processed CameraX frames without PreviewView's
 * additional viewfinder transforms. Only the static SurfaceRequest transform
 * (orientation/scale) is applied here.
 */
class HorizonPreviewTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), Preview.SurfaceProvider, TextureView.SurfaceTextureListener {
    private data class Active(
        val request: SurfaceRequest,
        val surface: Surface,
        val texture: SurfaceTexture
    )
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var waiting: SurfaceRequest? = null
    private var active: Active? = null
    private var destroyWhenFree: SurfaceTexture? = null
    private var bufferWidth = 1920
    private var bufferHeight = 1080
    private var baseRotation = 0

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    override fun onSurfaceRequested(request: SurfaceRequest) {
        waiting?.willNotProvideSurface()
        waiting = request
        bufferWidth = request.resolution.width
        bufferHeight = request.resolution.height
        request.setTransformationInfoListener(mainExecutor) { info ->
            if (waiting === request || active?.request === request) {
                // A processed CameraX Surface does not always have the
                // native camera transform. Do not apply the camera rotation
                // a second time when the Surface already carries it.
                baseRotation = if (info.hasCameraTransform()) {
                    -when (info.targetRotation) {
                        Surface.ROTATION_90 -> 90
                        Surface.ROTATION_180 -> 180
                        Surface.ROTATION_270 -> 270
                        else -> 0
                    }
                } else {
                    info.rotationDegrees
                }
                updatePreviewTransform()
            }
        }
        request.addRequestCancellationListener(mainExecutor) {
            if (waiting === request) waiting = null
        }
        tryProvide()
    }

    private fun tryProvide() {
        if (active != null) return
        val req = waiting ?: return
        val texture = surfaceTexture ?: return
        if (!isAvailable) return
        texture.setDefaultBufferSize(req.resolution.width, req.resolution.height)
        val surface = Surface(texture)
        val current = Active(req, surface, texture)
        active = current
        waiting = null
        updatePreviewTransform()
        req.provideSurface(surface, mainExecutor) {
            surface.release()
            if (active === current) active = null
            if (destroyWhenFree === texture) {
                texture.release()
                destroyWhenFree = null
            }
            tryProvide()
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        tryProvide()
        updatePreviewTransform()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        updatePreviewTransform()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        waiting?.willNotProvideSurface()
        waiting = null
        return if (active?.texture === texture) {
            destroyWhenFree = texture
            false
        } else {
            true
        }
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    private fun updatePreviewTransform() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val bw = bufferWidth.toFloat()
        val bh = bufferHeight.toFloat()
        val rad = Math.toRadians(baseRotation.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val rotatedW = abs(c * bw) + abs(s * bh)
        val rotatedH = abs(s * bw) + abs(c * bh)
        val zoom = max(vw / rotatedW, vh / rotatedH)

        val src = floatArrayOf(0f, 0f, vw, 0f, 0f, vh, vw, vh)
        val xs = floatArrayOf(-bw / 2, bw / 2, -bw / 2, bw / 2)
        val ys = floatArrayOf(-bh / 2, -bh / 2, bh / 2, bh / 2)
        val dst = FloatArray(8)
        for (i in 0 until 4) {
            dst[i * 2] = vw / 2 + zoom * (c * xs[i] - s * ys[i])
            dst[i * 2 + 1] = vh / 2 + zoom * (s * xs[i] + c * ys[i])
        }
        val matrix = Matrix()
        matrix.setPolyToPoly(src, 0, dst, 0, 4)
        setTransform(matrix)
    }
}
