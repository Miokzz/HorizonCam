package com.miokzz.horizoncam

import android.graphics.SurfaceTexture
import android.os.Handler
import android.view.Surface
import androidx.camera.core.ProcessingException
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import com.miokzz.horizoncam.stabilization.AngleMath

class HorizonSurfaceProcessor(
    private val state: HorizonState,
    private val glHandler: Handler,
    private val glExecutor: Executor,
    private val onRenderError: (Throwable) -> Unit
) : SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {

    private data class OutputTarget(
        val info: SurfaceOutput,
        val surface: Surface,
        val transform: FloatArray = FloatArray(16),
        val sensorToBufferGlDegrees: Float
    )

    private val egl = EglCore()
    private val renderer = OesRenderer()
    private var initialized = false
    private var released = false

    private var inputTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    // CameraX owns producer lifetime until provideSurface's result callback.
    private val inputSurfaces = IdentityHashMap<SurfaceTexture, Surface>()
    private val releaseCallbacks = mutableListOf<() -> Unit>()
    private var resourcesReleased = false

    // CameraEffect(PREVIEW | VIDEO_CAPTURE) can provide multiple output surfaces.
    // Keep all of them alive and draw each processed frame to every target.
    private val outputs = IdentityHashMap<SurfaceOutput, OutputTarget>()

    private val cameraTextureMatrix = FloatArray(16)
    private val vertexMatrix = FloatArray(16)
    @Volatile private var inputShape = "?"
    @Volatile private var outputShapes = "?"
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
            " E" + errorCount + " " + sync + " " + sensorFrameAgeMs.toInt() + "ms " + inputShape + ">" + outputShapes
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

            inputShape = "${request.resolution.width}x${request.resolution.height}"
            android.util.Log.i("HorizonCamGL", "Input $inputShape")
            val surfaceTexture = SurfaceTexture(rendererTextureId()).apply {
                setDefaultBufferSize(request.resolution.width, request.resolution.height)
                setOnFrameAvailableListener(this@HorizonSurfaceProcessor, glHandler)
            }
            val surface = Surface(surfaceTexture)
            inputTexture = surfaceTexture
            inputSurface = surface
            inputSurfaces[surfaceTexture] = surface

            request.provideSurface(surface, glExecutor) {
                // Release exactly the producer that CameraX has finished using,
                // including old inputs retained while a replacement is pending.
                surfaceTexture.setOnFrameAvailableListener(null)
                inputSurfaces.remove(surfaceTexture)
                surfaceTexture.release()
                surface.release()
                if (inputTexture === surfaceTexture) {
                    inputTexture = null
                    inputSurface = null
                }
                finishReleaseIfReady()
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
            val sensorToBuffer = FloatArray(9)
            surfaceOutput.sensorToBufferTransform.getValues(sensorToBuffer)
            // Android pixel matrices use Y-down; GL uses Y-up. The first
            // column yields the static native-sensor -> output rotation.
            val bufferRotation = AngleMath.sensorToBufferGlRotation(sensorToBuffer)
            outputs[surfaceOutput] = OutputTarget(surfaceOutput, surface,
                sensorToBufferGlDegrees = bufferRotation)
            outputShapes = outputs.values.joinToString(",") {
                "${it.info.size.width}x${it.info.size.height}@${it.sensorToBufferGlDegrees.toInt()}"
            }
            android.util.Log.i("HorizonCamGL", "Output $outputShapes targets=${surfaceOutput.targets}")
            registeredTargets = outputs.keys.fold(0) { mask, key -> mask or key.targets }
        } catch (t: Throwable) {
            surfaceOutput.close()
            throw ProcessingException().apply { initCause(t) }
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        ensureGlThread()
        if (released || surfaceTexture !== inputTexture) return
        try {
            // Drain producer buffers even before outputs arrive; otherwise the
            // camera can fill its queue during rebind and never publish again.
            surfaceTexture.updateTexImage()
            if (outputs.isEmpty()) return
            surfaceTexture.getTransformMatrix(cameraTextureMatrix)
            val timestamp = surfaceTexture.timestamp

            // Sensor interpolation happens once per camera frame. The same angle
            // is then applied in EACH destination's pixel metric, not a guessed
            // canonical 16:9 NDC metric (which stretches circles on 4:3/portrait).
            val frame = state.frameGeometry(timestamp, arrivalTimeNs = android.os.SystemClock.elapsedRealtimeNanos())
            sensorFrameAgeMs = frame.sensorAgeMs
            timestampsMatched = frame.timestampValid

            val targets = outputs.values.toList()
            for (target in targets) {
                if (!outputs.containsKey(target.info)) continue

                target.info.updateTransformMatrix(target.transform, cameraTextureMatrix)

                val size = target.info.size
                val aspect = size.width.toFloat() / size.height.coerceAtLeast(1)
                val zoom = if (state.isEnabled()) AngleMath.safeFixedCrop(aspect.toDouble()).toFloat() else 1f
                // CameraX still applies its static sensor-mount/target rotation
                // downstream. Proper 2D rotations commute, so the dynamic
                // correction has the SAME sign in native and target axes.
                // Adding a sensor-mount offset here would double-correct it.
                buildVertexMatrix(frame.rotationDegrees, zoom, aspect, vertexMatrix)
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
            if (resourcesReleased) {
                onReleased()
                return@post
            }
            releaseCallbacks.add(onReleased)
            if (!released) {
                released = true
                inputSurfaces.keys.forEach { it.setOnFrameAvailableListener(null) }
                outputs.values.toList().forEach { target ->
                    runCatching { egl.unregister(target.surface) }
                    target.info.close()
                }
                outputs.clear()
                registeredTargets = 0
            }
            // Keep the GL executor/context alive until every producer has been
            // returned by CameraX. Releasing sooner races encoder/camera writes.
            finishReleaseIfReady()
        }
    }

    private fun finishReleaseIfReady() {
        if (!released || inputSurfaces.isNotEmpty() || resourcesReleased) return
        resourcesReleased = true
        inputTexture = null
        inputSurface = null
        if (initialized) {
            renderer.release()
            egl.release()
            initialized = false
        }
        val callbacks = releaseCallbacks.toList()
        releaseCallbacks.clear()
        callbacks.forEach { it() }
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
        AngleMath.correctionGlMatrix(degrees.toDouble(), zoom.toDouble(), aspect.toDouble())
            .copyInto(out)
    }
}
