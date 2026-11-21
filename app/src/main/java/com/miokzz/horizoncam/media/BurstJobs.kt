package com.miokzz.horizoncam.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.miokzz.horizoncam.photo.BurstCapture
import com.miokzz.horizoncam.photo.fusion.BurstFusion
import com.miokzz.horizoncam.photo.fusion.FusionFrame
import com.miokzz.horizoncam.photo.fusion.FusionOptions
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object BurstJobs {
    data class State(val running: Boolean = false, val detail: String = "",
        val reference: Uri? = null, val output: Uri? = null)
    private val updates = MutableLiveData(State())
    val states: LiveData<State> get() = updates
    private val executor = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean()
    private val active = AtomicBoolean()

    fun cancel() { cancelled.set(true) }

    /** Takes ownership of all private scratch files, including when admission fails. */
    fun process(context: Context, frames: List<BurstCapture.FrameFile>, superResolution: Boolean) {
        val app = context.applicationContext
        if (!active.compareAndSet(false, true)) { cleanup(frames); return }
        cancelled.set(false)
        updates.value = State(true, "Preparando quadros…")
        executor.execute {
            var original: Uri? = null
            val result = runCatching {
                val referenceSensor = frames.firstNotNullOfOrNull { it.sensor }
                val candidates = frames.filter { it.sensor == null || referenceSensor == null || it.sensor == referenceSensor }
                val decoded = mutableListOf<FusionFrame>()
                candidates.forEachIndexed { index, frame ->
                    if (cancelled.get()) throw CancellationException()
                    val image = BurstLibrary.decode(frame, if (superResolution) 960 else 1280)
                    decoded += FusionFrame(image.data, image.width, image.height, image.exposureTimeNs, image.iso)
                    updates.postValue(State(true, "Lendo quadros · ${index + 1}/${candidates.size}"))
                }
                val output = BurstFusion.fuse(decoded, FusionOptions(superResolution = superResolution),
                    onProgress = { progress -> updates.postValue(State(true, "${progress.stage} · ${progress.completedFrames}/${progress.totalFrames}")) },
                    isCancelled = cancelled::get)
                if (cancelled.get()) throw CancellationException()
                val reference = candidates[output.referenceIndex].file
                val used = output.acceptedFrameCount
                if (used < 2) State(detail = "Movimento ou sinal insuficiente. Original preservado.", reference = BurstLibrary.publishOriginal(app, reference))
                else {
                    val detail = when {
                        output.superResolved -> "Super-resolução multiframe 2×"
                        superResolution -> "Fusão na resolução de trabalho · deslocamento subpixel insuficiente"
                        else -> "Noturno · redução temporal de ruído"
                    }
                    val bitmap = Bitmap.createBitmap(output.pixels, output.width, output.height, Bitmap.Config.ARGB_8888)
                    try {
                        val notes = "$detail · $used/${frames.size} quadros · ${output.width}×${output.height}"
                        val saved = AiPhotoFiles.save(app, Uri.fromFile(reference), bitmap, if (superResolution) "HC_MULTI" else "HC_NIGHT", notes)
                        Log.i("HorizonBurst", "$notes · cobertura subpixel ${output.phaseCoverage} · ${output.alignments}")
                        State(detail = notes, output = saved)
                    } finally { bitmap.recycle() }
                }
            }.getOrElse { error ->
                if (cancelled.get() || error is CancellationException) State(detail = "Fusão cancelada.", reference = original)
                else {
                    Log.e("HorizonBurst", "Falha na fusão", error)
                    if (original == null) original = runCatching { BurstLibrary.publishOriginal(app, frames.first().file) }.getOrNull()
                    State(detail = if (original == null) "Falha ao salvar a sequência." else "Falha na fusão. Original preservado.", reference = original)
                }
            }
            cleanup(frames)
            ContextCompat.getMainExecutor(app).execute { active.set(false); updates.value = result }
        }
    }

    private fun cleanup(frames: List<BurstCapture.FrameFile>) {
        frames.forEach { it.file.delete() }
        frames.firstOrNull()?.file?.parentFile?.delete()
    }
}
