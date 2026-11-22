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
import java.io.File

object BurstJobs {
    data class State(val running: Boolean = false, val detail: String = "")
    private val updates = MutableLiveData(State())
    val states: LiveData<State> get() = updates
    private val executor = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean()
    private val active = AtomicBoolean()

    fun cancel() { cancelled.set(true) }

    /** Takes ownership of all private scratch files, including when admission fails. */
    fun process(context: Context, frames: List<BurstCapture.FrameFile>, superResolution: Boolean, zoom: Float) {
        val app = context.applicationContext
        val referenceFile = frames.first().file
        if (frames.size == 1 || !active.compareAndSet(false, true)) {
            AutoPhotos.releaseBurst(app, referenceFile, useCaptured = true)
            cleanupExtras(frames)
            return
        }
        cancelled.set(false)
        updates.value = State(true, "Preparando quadros…")
        executor.execute {
            val temporary = File(referenceFile.parentFile, "${referenceFile.nameWithoutExtension}.fusion")
            val rendered = runCatching {
                val referenceSensor = frames.first().sensor
                val candidates = frames.filter { it.sensor == referenceSensor }
                if (candidates.size < 2) return@runCatching false
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
                if (used < 2) false
                else {
                    val detail = when {
                        output.superResolved -> "Super-resolução multiframe 2×"
                        superResolution -> "Fusão na resolução de trabalho · deslocamento subpixel insuficiente"
                        else -> "Noturno · redução temporal de ruído"
                    }
                    val bitmap = Bitmap.createBitmap(output.pixels, output.width, output.height, Bitmap.Config.ARGB_8888)
                    try {
                        val notes = "$detail · $used/${frames.size} quadros · ${output.width}×${output.height}"
                        val exif = android.media.ExifInterface(reference)
                        val iso = exif.getAttributeInt(android.media.ExifInterface.TAG_ISO_SPEED_RATINGS,100)
                        val exposure = exif.getAttributeDouble(android.media.ExifInterface.TAG_EXPOSURE_TIME,0.0)
                        val restored = try {
                            com.miokzz.horizoncam.ai.NativeRestorer.restore(app,bitmap,zoom,iso,1,
                                { p -> updates.postValue(State(true, "Revelando · ${p.completedTiles}/${p.totalTiles}")) },
                                cancelled::get, exposure)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { Log.w("HorizonBurst","Fusão concluída sem etapa neural", e); null }
                        val description = if (restored != null) {
                            "$notes | Horizon AI 2 | ${restored.model} | textura estimada | zoom ${zoom}x | " +
                                "input/output ${bitmap.width}x${bitmap.height} -> ${restored.bitmap.width}x${restored.bitmap.height}"
                        } else "$notes | sem restauracao neural"
                        try { AiPhotoFiles.encode(app, Uri.fromFile(reference), restored?.bitmap ?: bitmap, temporary, description) }
                            finally { restored?.bitmap?.recycle() }
                        if (cancelled.get()) throw CancellationException()
                        check(temporary.renameTo(AutoPhotos.encodedFor(referenceFile))) { "Falha ao confirmar a fusão privada." }
                        Log.i("HorizonBurst", "$notes · cobertura subpixel ${output.phaseCoverage} · ${output.alignments}")
                        true
                    } finally { bitmap.recycle() }
                }
            }.getOrElse { error ->
                if (error !is CancellationException) Log.e("HorizonBurst", "Fusão interrompida. Referência privada preservada.", error)
                false
            }
            try {
                AutoPhotos.releaseBurst(app, referenceFile, useCaptured = !rendered)
            } catch (e: Exception) {
                Log.e("HorizonBurst", "Referência privada aguardando recuperação da fila", e)
            } finally {
                temporary.delete()
                cleanupExtras(frames)
                ContextCompat.getMainExecutor(app).execute { active.set(false); updates.value = State() }
            }
        }
    }

    private fun cleanupExtras(frames: List<BurstCapture.FrameFile>) {
        val extras = frames.drop(1)
        extras.forEach { it.file.delete() }
        extras.firstOrNull()?.file?.parentFile?.delete()
    }
}
