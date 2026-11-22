package com.miokzz.horizoncam

import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import com.miokzz.horizoncam.ui.PreviewFitTransform
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Fixed-target landscape viewfinder for GPU video. CameraX's remaining STATIC
 * crop/rotation is applied once; dynamic world-horizon roll belongs only in GL.
 * UI/display rotation never changes this encoded landscape reference.
 */
class HorizonPreviewTextureView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : TextureView(context, attrs), Preview.SurfaceProvider, TextureView.SurfaceTextureListener {
    private class Pending(val request: SurfaceRequest, val watch: Any?) {
        var info: SurfaceRequest.TransformationInfo? = null
    }
    private data class Active(val pending: Pending, val surface: Surface, val texture: SurfaceTexture)
    private val main = ContextCompat.getMainExecutor(context)
    private var waiting: Pending? = null
    private var active: Active? = null
    private val retainedTextures = Collections.newSetFromMap(IdentityHashMap<SurfaceTexture, Boolean>())
    private var firstFrameListener: (() -> Unit)? = null
    private var frameWatch: Any? = null

    init {
        surfaceTextureListener = this
        isOpaque = false
        alpha = 0f
    }

    override fun onSurfaceRequested(request: SurfaceRequest) = attach(request, frameWatch)

    private fun attach(request: SurfaceRequest, watch: Any?) {
        main.execute {
            if (watch !== frameWatch) {
                request.willNotProvideSurface()
                return@execute
            }
            waiting?.let(::decline)
            val next = Pending(request, watch)
            waiting = next
            request.setTransformationInfoListener(main) { info ->
                // An old request's callback must never change a newer frame.
                if (waiting !== next && active?.pending !== next) return@setTransformationInfoListener
                next.info = info
                android.util.Log.i("HorizonCamPreview", "${request.resolution.width}x${request.resolution.height} " +
                    "crop=${info.cropRect} rotation=${info.rotationDegrees} cameraTransform=${info.hasCameraTransform()}")
                if (active?.pending === next) updateTransformForLayout()
            }
            request.addRequestCancellationListener(main) {
                if (waiting === next) {
                    waiting = null
                    request.clearTransformationInfoListener()
                }
            }
            tryProvide()
        }
    }

    /** Check readiness from a newly bound producer, even when the camera device stayed open. */
    fun watchFirstFrame(listener: (() -> Unit)?) {
        firstFrameListener = listener
        frameWatch = listener?.let { Any() }
    }

    fun sessionSurfaceProvider(listener: () -> Unit): Preview.SurfaceProvider {
        watchFirstFrame(listener)
        val watch = frameWatch
        return Preview.SurfaceProvider { attach(it, watch) }
    }

    private fun decline(pending: Pending) {
        pending.request.clearTransformationInfoListener()
        pending.request.willNotProvideSurface()
    }

    private fun tryProvide() {
        if (active != null) return
        val pending = waiting ?: return
        val request = pending.request
        val texture = surfaceTexture ?: return
        if (!isAvailable) return
        texture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(texture)
        val current = Active(pending, surface, texture)
        active = current
        waiting = null
        updateTransformForLayout()
        request.provideSurface(surface, main) {
            request.clearTransformationInfoListener()
            surface.release()
            if (retainedTextures.remove(texture)) texture.release()
            if (active === current) {
                active = null
                alpha = 0f
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
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        updateTransformForLayout()
    }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        waiting?.let(::decline)
        waiting = null
        val current = active
        return if (current?.texture === texture) {
            retainedTextures.add(texture)
            // Ask CameraX to stop using this detached producer. Keep it alive
            // until the matching provideSurface result acknowledges completion.
            current.pending.request.invalidate()
            false
        } else true
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        val current = active ?: return
        if (current.texture !== texture || current.pending.info == null || current.pending.watch !== frameWatch) return
        val listener = firstFrameListener ?: return
        firstFrameListener = null
        listener()
    }
    override fun performClick(): Boolean = super.performClick()

    private fun updateTransformForLayout() {
        val current = active ?: return
        val info = current.pending.info ?: run { alpha = 0f; return }
        if (width <= 0 || height <= 0) return
        val size = current.pending.request.resolution
        val crop = info.cropRect
        if (crop.isEmpty) return
        // Normal video uses PreviewView. In this GL path cameraTransform=false,
        // so CameraX rotationDegrees is the exact remaining static rotation.
        // For a rear direct-camera producer, undo its implicit orientation first;
        // fixed target90 replaces any display-dependent correction.
        val implicitCameraDegrees = if (info.hasCameraTransform()) info.rotationDegrees + 90 else null
        val fit = PreviewFitTransform.calculate(size.width, size.height,
            PreviewFitTransform.Crop(crop.left.toDouble(),crop.top.toDouble(),crop.right.toDouble(),crop.bottom.toDouble()),
            info.rotationDegrees, width, height, implicitCameraDegrees)
        val matrix = Matrix().apply { setValues(fit.textureViewTransform.values()) }
        setTransform(matrix)
        // With FIT, invalid pixels outside crop must not leak into letterboxes.
        clipBounds = Rect(ceil(fit.visible.left).toInt(),ceil(fit.visible.top).toInt(),
            floor(fit.visible.right).toInt(),floor(fit.visible.bottom).toInt())
        alpha = 1f
    }
}
