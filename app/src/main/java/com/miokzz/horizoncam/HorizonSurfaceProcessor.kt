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
import com.miokzz.horizoncam.stabilization.FrameMotion
import com.miokzz.horizoncam.stabilization.FrameShift
import com.miokzz.horizoncam.stabilization.MotionViewport
import com.miokzz.horizoncam.stabilization.safeShiftFactor
import kotlin.math.abs

class HorizonSurfaceProcessor(
    private val state: HorizonState,
    private val glHandler: Handler,
    private val glExecutor: Executor,
    private val nativeEisActive: () -> Boolean?,
    private val onRenderError: (Throwable) -> Unit
) : SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {

    private data class OutputTarget(
        val info: SurfaceOutput,
        val surface: Surface,
        val transform: FloatArray = FloatArray(16),
        val sensorToBufferGlDegrees: Float,
        val aspect: Float = info.size.width.toFloat() / info.size.height.coerceAtLeast(1),
        val crop: Float = AngleMath.safeFixedCrop(aspect.toDouble()).toFloat(),
        val motionViewport: MotionViewport?,
        var shift: FrameShift = FrameShift.ZERO
    )

    internal data class PreviewCorrection(val degrees: Float, val zoom: Float, val aspect: Float, val shift: FrameShift) {
        fun undo(x: Double, y: Double): Pair<Double, Double> =
            AngleMath.correctionMatrix(degrees.toDouble(), zoom.toDouble(), aspect.toDouble())
                .inverse().apply(x - shift.x, y - shift.y)
    }

    private val egl = EglCore()
    private val renderer = OesRenderer()
    private val motion = FrameMotion(LumaFrameSampler.WIDTH, LumaFrameSampler.HEIGHT)
    private val luma = LumaFrameSampler()
    private var motionReference: OutputTarget? = null
    private var motionUnavailable = false
    @Volatile internal var previewCorrection: PreviewCorrection? = null
        private set
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

    // All mutations and drawing run on the same GL looper. Replace the array only when
    // CameraX adds/removes a surface, including exceptional removal while drawing a frame.
    private var drawTargets = emptyArray<OutputTarget>()
    private fun updateOutputs() {
        drawTargets = outputs.values.toTypedArray()
        registeredTargets = outputs.keys.fold(0) { mask, key -> mask or key.targets }
    }

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
    @Volatile private var synchronizedFrames = 0L
    @Volatile private var orientationFrames = 0L
    @Volatile private var motionPairs = 0L
    @Volatile private var visualEisFrames = 0L
    @Volatile private var motionSampleMicros = 0L

    fun diagnostics(): String {
        val mask = registeredTargets
        val p = if (mask and CameraEffect.PREVIEW != 0) 1 else 0
        val v = if (mask and CameraEffect.VIDEO_CAPTURE != 0) 1 else 0
        val sync = if (timestampsMatched) "SYNC" else "LATEST"
        val translation = when {
            nativeEisActive() == true -> "HAL"
            nativeEisActive() == null -> "HAL solicitado, sem leitura"
            motionUnavailable -> "indisponível"
            else -> "visual=$visualEisFrames/$motionPairs scan=${motionSampleMicros / 1000.0}ms"
        }
        return "P" + p + " V" + v + " F" + renderedFrames +
            " E" + errorCount + " " + sync + " " + sensorFrameAgeMs.toInt() + "ms " +
            "matched=" + synchronizedFrames + "/" + orientationFrames + " " + inputShape + ">" + outputShapes +
            " EIS=$translation"
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
            synchronizedFrames = 0L
            orientationFrames = 0L
            motion.reset()
            motionReference = null
            previewCorrection = null
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
                updateOutputs()
                surfaceOutput.close()
            }

            egl.register(surface)
            val sensorToBuffer = FloatArray(9)
            surfaceOutput.sensorToBufferTransform.getValues(sensorToBuffer)
            // Android pixel matrices use Y-down; GL uses Y-up. The first
            // column yields the static native-sensor -> output rotation.
            val bufferRotation = AngleMath.sensorToBufferGlRotation(sensorToBuffer)
            val linear = AngleMath.Matrix2(sensorToBuffer[0].toDouble(), -sensorToBuffer[1].toDouble(),
                -sensorToBuffer[3].toDouble(), sensorToBuffer[4].toDouble())
            val width = surfaceOutput.size.width
            val height = surfaceOutput.size.height
            val usableTransform = abs(linear.m00 * linear.m11 - linear.m01 * linear.m10) > 1e-12
            val motionViewport = if (usableTransform && width > 0 && height > 0) MotionViewport(width, height,
                linear, AngleMath.safeFixedCrop(width.toDouble() / height)) else null
            outputs[surfaceOutput] = OutputTarget(surfaceOutput, surface,
                sensorToBufferGlDegrees = bufferRotation, motionViewport = motionViewport)
            outputShapes = outputs.values.joinToString(",") {
                "${it.info.size.width}x${it.info.size.height}@${it.sensorToBufferGlDegrees.toInt()}"
            }
            android.util.Log.i("HorizonCamGL", "Output $outputShapes targets=${surfaceOutput.targets}")
            updateOutputs()
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
            if (state.isEnabled()) {
                orientationFrames++
                if (frame.timestampValid) synchronizedFrames++
            }

            val shiftFactor = measureSharedTranslation(frame, timestamp)

            for (target in drawTargets) {

                target.info.updateTransformMatrix(target.transform, cameraTextureMatrix)

                val size = target.info.size
                val aspect = target.aspect
                val zoom = if (state.isEnabled()) target.crop else 1f
                // CameraX still applies its static sensor-mount/target rotation
                // downstream. Proper 2D rotations commute, so the dynamic
                // correction has the SAME sign in native and target axes.
                // Adding a sensor-mount offset here would double-correct it.
                buildVertexMatrix(frame.rotationDegrees, zoom, aspect, vertexMatrix)
                val shift = target.shift * shiftFactor
                vertexMatrix[12] = shift.x.toFloat()
                vertexMatrix[13] = shift.y.toFloat()
                try {
                    egl.draw(target.surface, size.width, size.height, timestamp) {
                        renderer.draw(target.transform, vertexMatrix)
                    }
                    if (target.info.targets and CameraEffect.PREVIEW != 0) {
                        previewCorrection = PreviewCorrection(frame.rotationDegrees, zoom, aspect, shift)
                    }
                } catch (surfaceError: Throwable) {
                    // A video encoder can close its Surface while the preview
                    // remains active (and vice versa). One output failure must
                    // not kill the CameraX GL thread or the other output.
                    reportRenderError(surfaceError)
                    outputs.remove(target.info)
                    runCatching { egl.unregister(target.surface) }
                    runCatching { target.info.close() }
                    updateOutputs()
                }
            }
            renderedFrames++
            if (renderedFrames % 300L == 0L) {
                android.util.Log.i("HorizonCamGL", "Frame timing ${diagnostics()}")
            }
        } catch (t: Throwable) {
            // Log and expose an explicit error; do not crash the GL looper.
            reportRenderError(t)
        }
    }

    private fun measureSharedTranslation(frame: FrameGeometry, timestamp: Long): Double {
        if (!state.isEnabled() || motionUnavailable || nativeEisActive() != false || !frame.orientationAvailable ||
            frame.sensorAgeMs > 100f || drawTargets.any { it.motionViewport == null }) {
            motion.reset()
            return 0.0
        }
        val reference = motionReference?.takeIf { outputs[it.info] === it }
            ?: (drawTargets.firstOrNull { it.info.targets and CameraEffect.PREVIEW != 0 } ?: drawTargets.first()).also {
                motion.reset()
                motionReference = it
            }
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        val correction = try {
            egl.makeOffscreenCurrent()
            reference.info.updateTransformMatrix(reference.transform, cameraTextureMatrix)
            buildVertexMatrix(frame.rotationDegrees, reference.crop, reference.aspect, vertexMatrix)
            luma.read(renderer, reference.transform, vertexMatrix, motion.input)
            motion.advance(timestamp)
        } catch (error: Exception) {
            motionUnavailable = true
            android.util.Log.w("HorizonCamGL", "EIS visual indisponível; roll mantido", error)
            return 0.0
        }
        motionSampleMicros = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1000
        motionPairs++
        if (motion.matchedPatches >= 4) visualEisFrames++
        val viewport = checkNotNull(reference.motionViewport)
        var factor = 1.0
        drawTargets.forEach { target ->
            target.shift = checkNotNull(target.motionViewport).shiftFrom(viewport, correction)
            factor = minOf(factor, safeShiftFactor(frame.rotationDegrees.toDouble(), target.crop.toDouble(),
                target.aspect.toDouble(), target.shift))
        }
        return factor
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
                updateOutputs()
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
            egl.makeOffscreenCurrent()
            luma.release()
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
