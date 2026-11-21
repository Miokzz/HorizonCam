package com.miokzz.horizoncam.photo

import android.os.Looper
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/** Serial JPEG acquisition. Private scratch frames are transferred to processing only on success. */
class BurstCapture(
    cache: File, private val camera: Camera, private val capture: ImageCapture,
    private val main: Executor, private val night: Boolean,
    private val physicalId: () -> String?,
    private val progress: (Int, Int) -> Unit,
    private val done: (Result<List<FrameFile>>) -> Unit
) {
    data class FrameFile(val file: File, val ev: Float, val sensor: String?)
    private val dir = File(cache, "hcburst-${UUID.randomUUID()}")
    private val exposure = camera.cameraInfo.exposureState
    private val base = exposure.exposureCompensationIndex
    private val step = exposure.exposureCompensationStep.toFloat()
    private val highlight = if (night && exposure.isExposureCompensationSupported && step > 0f) {
        (base - (1f / step).roundToInt().coerceAtLeast(1)).coerceIn(exposure.exposureCompensationRange.lower,
            exposure.exposureCompensationRange.upper)
    } else base
    private val bracket = intArrayOf(base, base, highlight, base, base)
    private val frames = mutableListOf<FrameFile>()
    private var applied = base
    private var cancelled = false
    private var finished = false

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!dir.mkdir()) { finish(IllegalStateException("Armazenamento temporário indisponível.")); return }
        next()
    }
    fun cancel() { cancelled = true }

    private fun next() {
        if (finished) return
        if (cancelled) { finish(CancellationException()); return }
        if (frames.size == bracket.size) { finish(null); return }
        progress(frames.size, bracket.size)
        val index = bracket[frames.size]
        if (index == applied) { shoot(index); return }
        try {
            val ev = camera.cameraControl.setExposureCompensationIndex(index)
            ev.addListener({
                runCatching { ev.get() }.fold(onSuccess = { actual ->
                    applied = actual
                    if (cancelled) finish(CancellationException()) else shoot(actual)
                }, onFailure = { finish(it) })
            }, main)
        } catch (e: Exception) { finish(e) }
    }

    private fun shoot(index: Int) {
        val file = File(dir, "frame-${frames.size}.jpg")
        val output = ImageCapture.OutputFileOptions.Builder(file).build()
        try {
            capture.takePicture(output, main, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    frames += FrameFile(file, (index - base) * step, physicalId())
                    next()
                }
                override fun onError(exception: ImageCaptureException) = finish(exception)
            })
        } catch (e: Exception) { finish(e) }
    }

    private fun finish(error: Throwable?) {
        if (finished) return
        finished = true
        val complete = {
            if (error != null || cancelled) {
                dir.listFiles()?.forEach { it.delete() }
                dir.delete()
                done(Result.failure(error ?: CancellationException()))
            } else done(Result.success(frames.toList()))
        }
        if (applied == base) { complete(); return }
        try {
            val reset = camera.cameraControl.setExposureCompensationIndex(base)
            reset.addListener({
                runCatching { reset.get() }.onFailure { Log.w("HorizonBurst", "Restauração de EV interrompida", it) }
                complete()
            }, main)
        } catch (e: Exception) { Log.w("HorizonBurst", "Câmera encerrada durante restauração de EV", e); complete() }
    }
}
