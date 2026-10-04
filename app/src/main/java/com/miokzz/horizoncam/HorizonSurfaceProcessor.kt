package com.miokzz.horizoncam

import android.graphics.SurfaceTexture
import android.opengl.Matrix
import android.os.Handler
import android.view.Surface
import androidx.camera.core.ProcessingException
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import kotlin.math.cos
import kotlin.math.sin

class HorizonSurfaceProcessor(
    private val state: HorizonState,
    private val glHandler: Handler,
    private val glExecutor: Executor,
    private val onRenderError: (Throwable) -> Unit
) : SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {

    private data class OutputTarget(
        val info: SurfaceOutput,
        val surface: Surface,
        val transform: FloatArray = FloatArray(16)
    )

    private val egl = EglCore()
    private val renderer = OesRenderer()
    private var initialized = false
    private var released = false

    private var inputTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null

    // CameraEffect(PREVIEW | VIDEO_CAPTURE) can provide multiple output surfaces.
    // Keep all of them alive and draw each processed frame to every target.
    private val outputs = IdentityHashMap<SurfaceOutput, OutputTarget>()

    private val cameraTextureMatrix = FloatArray(16)
    private val vertexMatrix = FloatArray(16)
    private val canonicalAspect = 16f / 9f
    @Volatile private var sensorFrameAgeMs = 0f
    @Volatile private var timestampsMatched = false
    @Volatile private var errorCount = 0
    private var lastErrorAtMs = 0L
    @Volatile private var registeredTargets = 0
    @Volatile private var renderedFrames = 0L

    fun diagnostics(): String {
        val mask = registeredTargets
        val p = if (mask and CameraEffect.PREVIEW != 0) 1 else 0
        val v = if (mask and CameraEffect.VIDEO_CAPTURE != 0) 1 else 0
        val sync = if (timestampsMatched) "SYNC" else "LATEST"
        return "P" + p + " V" + v + " F" + renderedFrames +
            " E" + errorCount + " " + sync + " " + sensorFrameAgeMs.toInt() + "ms"
    }

    override fun onInputSurface(request: SurfaceRequest) {
        ensureGlThread()
        if (released) {
            request.willNotProvideSurface()
            return
        }

        try {
            ensureInitialized()

            inputTexture?.setOnFrameAvailableListener(null)
            inputSurface?.release()
            inputTexture?.release()

            val surfaceTexture = SurfaceTexture(rendererTextureId()).apply {
                setDefaultBufferSize(request.resolution.width, request.resolution.height)
                setOnFrameAvailableListener(this@HorizonSurfaceProcessor, glHandler)
            }
            val surface = Surface(surfaceTexture)
            inputTexture = surfaceTexture
            inputSurface = surface

            request.provideSurface(surface, glExecutor) {
                if (inputSurface === surface) {
                    surfaceTexture.setOnFrameAvailableListener(null)
                    surfaceTexture.release()
                    surface.release()
                    inputTexture = null
                    inputSurface = null
                }
            }
        } catch (t: Throwable) {
            request.willNotProvideSurface()
            throw ProcessingException().apply { initCause(t) }
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        ensureGlThread()
        if (released) {
            surfaceOutput.close()
            return
        }

        try {
            ensureInitialized()

            val surface = surfaceOutput.getSurface(glExecutor) {
                val target = outputs.remove(surfaceOutput)
                if (target != null) {
                    egl.unregister(target.surface)
                }
                registeredTargets = outputs.keys.fold(0) { mask, key -> mask or key.targets }
                surfaceOutput.close()
            }

            egl.register(surface)
            outputs[surfaceOutput] = OutputTarget(surfaceOutput, surface)
            registeredTargets = outputs.keys.fold(0) { mask, key -> mask or key.targets }
        } catch (t: Throwable) {
            surfaceOutput.close()
            throw ProcessingException().apply { initCause(t) }
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        ensureGlThread()
        if (released || surfaceTexture !== inputTexture) return
        if (outputs.isEmpty()) return

        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(cameraTextureMatrix)
            val timestamp = surfaceTexture.timestamp

            // Compute the canonical orientation/crop exactly ONCE for each
            // camera frame, synchronized with the sensor's timestamp.
            // Preview and encoder receive the SAME geometry. CameraX's
            // SurfaceOutput matrices handle only each destination's static
            // crop/rotation metadata.
            val frame = state.frameGeometry(timestamp, canonicalAspect)
            sensorFrameAgeMs = frame.sensorAgeMs
            timestampsMatched = frame.timestampValid
            buildVertexMatrix(
                frame.rotationDegrees,
                frame.cropScale,
                canonicalAspect,
                vertexMatrix
            )

            val targets = outputs.values.toList()
            for (target in targets) {
                if (!outputs.containsKey(target.info)) continue

                target.info.updateTransformMatrix(target.transform, cameraTextureMatrix)

                val size = target.info.size
                try {
                    egl.draw(target.surface, size.width, size.height, timestamp) {
                        renderer.draw(target.transform, vertexMatrix)
                    }
                } catch (surfaceError: Throwable) {
                    // A video encoder can close its Surface while the preview
                    // remains active (and vice versa). One output failure must
                    // not kill the CameraX GL thread or the other output.
                    reportRenderError(surfaceError)
                    outputs.remove(target.info)
                    runCatching { egl.unregister(target.surface) }
                    runCatching { target.info.close() }
                    registeredTargets = outputs.keys.fold(0) { mask, key -> mask or key.targets }
                }
            }
            renderedFrames++
        } catch (t: Throwable) {
            // Log and expose an explicit error; do not crash the GL looper.
            reportRenderError(t)
        }
    }

    private fun reportRenderError(error: Throwable) {
        errorCount++
        android.util.Log.e("HorizonCamGL", "GPU processing failed", error)
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastErrorAtMs > 1800L) {
            lastErrorAtMs = now
            onRenderError(error)
        }
    }

    fun release(onReleased: () -> Unit = {}) {
        glHandler.post {
            if (!released) {
                released = true

                inputTexture?.setOnFrameAvailableListener(null)
                inputSurface?.release()
                inputTexture?.release()
                inputSurface = null
                inputTexture = null

                outputs.values.toList().forEach { target ->
                    egl.unregister(target.surface)
                    target.info.close()
                }
                outputs.clear()
                registeredTargets = 0

                if (initialized) {
                    renderer.release()
                    egl.release()
                    initialized = false
                }
            }
            onReleased()
        }
    }

    private var inputTextureId = 0

    private fun ensureInitialized() {
        if (initialized) return
        egl.init()
        inputTextureId = renderer.init()
        initialized = true
    }

    private fun rendererTextureId(): Int {
        check(inputTextureId != 0) { "Renderer texture is not initialized" }
        return inputTextureId
    }

    private fun ensureGlThread() {
        check(Thread.currentThread() === glHandler.looper.thread) {
            "SurfaceProcessor must run on HorizonCam GL thread"
        }
    }

    private fun buildVertexMatrix(
        degrees: Float,
        zoom: Float,
        aspect: Float,
        out: FloatArray
    ) {
        Matrix.setIdentityM(out, 0)

        val radians = Math.toRadians(degrees.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val a = aspect.coerceAtLeast(0.01f)

        out[0] = zoom * c
        out[1] = zoom * s * a
        out[4] = -zoom * s / a
        out[5] = zoom * c
    }
}
