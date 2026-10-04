package com.miokzz.horizoncam

import android.graphics.SurfaceTexture
import android.opengl.Matrix
import android.os.Handler
import android.view.Surface
import androidx.camera.core.ProcessingException
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.util.concurrent.Executor
import kotlin.math.cos
import kotlin.math.sin

class HorizonSurfaceProcessor(
    private val state: HorizonState,
    private val glHandler: Handler,
    private val glExecutor: Executor
) : SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {

    private val egl = EglCore()
    private val renderer = OesRenderer()
    private var initialized = false
    private var released = false

    private var inputTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var outputInfo: SurfaceOutput? = null
    private var outputSurface: Surface? = null

    private val cameraTextureMatrix = FloatArray(16)
    private val outputTextureMatrix = FloatArray(16)
    private val vertexMatrix = FloatArray(16)

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

            outputSurface?.let { egl.unregister(it) }
            outputInfo?.close()

            val surface = surfaceOutput.getSurface(glExecutor) {
                if (outputInfo === surfaceOutput) {
                    outputSurface?.let { egl.unregister(it) }
                    outputSurface = null
                    outputInfo = null
                }
                surfaceOutput.close()
            }

            egl.register(surface)
            outputInfo = surfaceOutput
            outputSurface = surface
        } catch (t: Throwable) {
            surfaceOutput.close()
            throw ProcessingException().apply { initCause(t) }
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        ensureGlThread()
        if (released || surfaceTexture !== inputTexture) return

        val outInfo = outputInfo ?: return
        val outSurface = outputSurface ?: return

        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(cameraTextureMatrix)
            outInfo.updateTransformMatrix(outputTextureMatrix, cameraTextureMatrix)

            val size = outInfo.size
            val aspect = if (size.height == 0) 16f / 9f else size.width.toFloat() / size.height.toFloat()
            buildVertexMatrix(
                state.correctionDegrees(),
                state.safeCropScale(aspect),
                aspect,
                vertexMatrix
            )

            egl.draw(
                outSurface,
                size.width,
                size.height,
                surfaceTexture.timestamp
            ) {
                renderer.draw(outputTextureMatrix, vertexMatrix)
            }
        } catch (t: Throwable) {
            inputTexture?.let { current ->
                if (!current.isReleased) {
                    // CameraX will rebuild the pipeline on a new request if needed.
                }
            }
            throw RuntimeException("Horizon GL frame processing failed", t)
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

                outputSurface?.let { egl.unregister(it) }
                outputInfo?.close()
                outputSurface = null
                outputInfo = null

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

        // Pixel-correct 2D rotation in NDC for a non-square viewport,
        // with uniform zoom to hide exposed corners.
        out[0] = zoom * c
        out[1] = zoom * s * a
        out[4] = -zoom * s / a
        out[5] = zoom * c
    }
}
