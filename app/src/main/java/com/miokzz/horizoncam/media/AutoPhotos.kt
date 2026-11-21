package com.miokzz.horizoncam.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.miokzz.horizoncam.ai.CaptureMetrics
import com.miokzz.horizoncam.ai.NativeRestorer
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor

/** Capture owns a private original until exactly one final MediaStore row has been published. */
object AutoPhotos {
    data class State(val running: Boolean, val message: String, val uri: Uri? = null, val zoom: Float = 1f)
    private val updates = MutableLiveData(State(false, ""))
    val states: LiveData<State> = updates
    internal fun post(state: State) = updates.postValue(state)
    internal fun directory(context: Context) = File(context.noBackupFilesDir, "pending-photos")

    fun capture(context: Context, capture: ImageCapture, zoom: Float, crop: Float, executor: Executor, finished: (Throwable?) -> Unit) {
        val dir = directory(context)
        val name = "${System.currentTimeMillis()}_${zoom.toRawBits()}_${crop.toRawBits()}_${UUID.randomUUID()}"
        val part = File(dir, "$name.part")
        try {
            check(dir.isDirectory || dir.mkdirs())
            require(dir.usableSpace > 100L * 1024 * 1024) { "Espaço livre insuficiente." }
            val options = ImageCapture.OutputFileOptions.Builder(part).build()
            capture.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    val pending = File(dir, "$name.jpg")
                    val error = runCatching {
                        check(part.renameTo(pending)) { "Falha ao concluir o arquivo privado." }
                        enqueue(context, pending)
                    }.exceptionOrNull()
                    finished(error)
                }
                override fun onError(exception: ImageCaptureException) {
                    part.delete()
                    finished(exception)
                }
            })
        } catch (e: Exception) { part.delete(); finished(e) }
    }

    fun recover(context: Context) {
        val dir = directory(context)
        val prefs = context.getSharedPreferences("horizoncam", Context.MODE_PRIVATE)
        prefs.getString("autoFinalUri", null)?.let {
            post(State(false, prefs.getString("autoFinalMessage", "Foto salva").orEmpty(), Uri.parse(it),
                prefs.getFloat("autoFinalZoom", 1f)))
        }
        val files = dir.listFiles().orEmpty()
        files.filter { it.extension == "part" && System.currentTimeMillis() - it.lastModified() > 86_400_000 }
            .forEach { it.delete() }
        files.filter { it.extension == "jpg" }.sortedBy { it.name }.forEach { enqueue(context, it) }
    }

    private fun enqueue(context: Context, file: File) {
        val request = OneTimeWorkRequestBuilder<AutoPhotoWorker>()
            .setInputData(Data.Builder().putString("source", file.name).build()).build()
        // Workers drain in capture order. Recovery may append an already-completed source;
        // its private file is removed only after durable publication, making that entry a no-op.
        WorkManager.getInstance(context).enqueueUniqueWork("horizon-photo-queue", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

class AutoPhotoWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    private val app = applicationContext
    private val resolver = app.contentResolver


    override fun doWork(): Result {
        val name = inputData.getString("source") ?: return Result.success()
        if (!name.matches(Regex("[0-9]+_[0-9]+_(?:[0-9]+_)?[0-9a-f-]{36}\\.jpg"))) return Result.success()
        val file = File(AutoPhotos.directory(app), name)
        if (!file.isFile) return Result.success()
        val zoom = Float.fromBits(name.substringAfter('_').substringBefore('_').toInt())
        val parts = name.split('_')
        val crop = if (parts.size == 4) Float.fromBits(parts[2].toInt()) else 1f
        val marker = File(file.parentFile, "${file.nameWithoutExtension}.uri")
        val thermal = app.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0
        if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) {
            AutoPhotos.post(AutoPhotos.State(true, "Restauração aguardando resfriamento"))
            return Result.retry()
        }
        return try {
            val target = reserve(file, marker)
            var message = "Foto restaurada"
            if (!published(target)) {
                val restored = try {
                    restore(file, target, zoom, crop)
                    true
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.w("HorizonAuto", "Restauro indisponível para $name", e); false }
                catch (e: OutOfMemoryError) { Log.w("HorizonAuto", "Limite de memória para $name", e); false }
                if (!restored) {
                    if (isStopped) throw CancellationException()
                    // A failed JPEG write can remove the pending row. Reserve a new one if needed.
                    val originalTarget = reserve(file, marker)
                    if (crop > 1f) {
                        val framed = decodeFramed(file, crop)
                        try { AiPhotoFiles.save(app, Uri.fromFile(file), framed, "HC",
                            "Zoom ${zoom}x · JPEG da câmera com recorte digital", originalTarget) }
                        finally { framed.recycle() }
                    } else {
                        requireNotNull(resolver.openOutputStream(originalTarget, "wt")).use { out ->
                            file.inputStream().use { it.copyTo(out) }
                        }
                        check(resolver.update(originalTarget, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
                    }
                    message = "JPEG da câmera salvo · restauração indisponível"
                }
            }
            val saved = Uri.parse(marker.readText())
            check(app.getSharedPreferences("horizoncam", Context.MODE_PRIVATE).edit()
                .putString("autoFinalUri", saved.toString()).putString("autoFinalMessage", message)
                .putFloat("autoFinalZoom", zoom).commit())
            if (file.delete()) marker.delete()
            AutoPhotos.post(AutoPhotos.State(false, message, saved, zoom))
            Result.success()
        } catch (e: CancellationException) { Result.retry() }
        catch (e: Exception) {
            Log.e("HorizonAuto", "Falha ao publicar; captura privada mantida", e)
            AutoPhotos.post(AutoPhotos.State(true, "Foto guardada · aguardando nova tentativa"))
            Result.retry()
        }
    }

    private fun restore(file: File, target: Uri, zoom: Float, crop: Float) {
        val exif = ExifInterface(file)
        val iso = exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 100)
        val source = decodeFramed(file, crop)
        try {
            var lastPercent = -1
            val power = app.getSystemService(PowerManager::class.java)
            var tooHot = false
            val upscale = com.miokzz.horizoncam.camera.StillFraming.reconstructionScale(source.width, source.height, crop, NativeRestorer.MAX_PIXELS)
            val result = NativeRestorer.restore(app, source, zoom, iso, upscale, { progress ->
                val percent = 100 * progress.completedTiles / progress.totalTiles
                if (lastPercent != percent) {
                    AutoPhotos.post(AutoPhotos.State(true, "Revelando foto · $percent%"))
                    lastPercent = percent
                    tooHot = (power?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE
                    if (tooHot) AutoPhotos.post(AutoPhotos.State(true, "Restauração aguardando resfriamento"))
                }
            }, { isStopped || tooHot })
            try {
                if (isStopped) throw CancellationException()
                AiPhotoFiles.save(app, Uri.fromFile(file), result.bitmap, "HC",
                    "Horizon AI · Real-ESRGAN general-x4v3 · textura estimada · zoom ${zoom}x", target)
                val before = CaptureMetrics.summary(source)
                val after = CaptureMetrics.summary(result.bitmap)
                Log.i("HorizonAuto", "scene zoom=$zoom residual=$crop iso=$iso before=$before after=$after pssKb=${android.os.Debug.getPss()}")
                Log.i("HorizonAuto", "Real-ESRGAN ${source.width}x${source.height} grid=${result.modelWidth}x${result.modelHeight} ms=${result.elapsedMs}")
            } finally { result.bitmap.recycle() }
        } finally { source.recycle() }
    }

    private fun decodeFramed(file: File, crop: Float): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = minOf(1.0, kotlin.math.sqrt(NativeRestorer.MAX_PIXELS.toDouble() / (w.toLong() * h)))
            val outW = (w * scale).toInt().coerceAtLeast(1)
            val outH = (h * scale).toInt().coerceAtLeast(1)
            decoder.setTargetSize(outW, outH)
            val box = com.miokzz.horizoncam.camera.StillFraming.centerCrop(outW, outH, crop)
            decoder.crop = android.graphics.Rect(box.left, box.top, box.left + box.width, box.top + box.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        }

    private fun pendingStatus(uri: Uri): Int? = resolver.query(uri, arrayOf(MediaStore.Images.Media.IS_PENDING),
        null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else null }

    private fun published(uri: Uri) = pendingStatus(uri) == 0

    private fun reserve(file: File, marker: File): Uri {
        if (marker.isFile) {
            val uri = Uri.parse(marker.readText())
            if (pendingStatus(uri) != null) return uri
        }
        val values = CaptureLibrary.values(photo = true).apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "HC_${file.name}")
            put(MediaStore.Images.Media.DATE_TAKEN, file.name.substringBefore('_').toLong())
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        try {
            val temp = File(marker.parentFile, "${marker.name}.tmp")
            temp.writeText(uri.toString())
            check(temp.renameTo(marker))
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
        return uri
    }
}
