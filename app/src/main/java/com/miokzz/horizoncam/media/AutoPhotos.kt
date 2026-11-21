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
            val encoded = File(file.parentFile, "${file.nameWithoutExtension}.result")
            val previous = if (marker.isFile) Uri.parse(marker.readText()) else null
            val saved = if (previous != null && published(previous)) previous else {
                if (!encoded.isFile) {
                    val temp = File(file.parentFile, "${file.nameWithoutExtension}.rendering")
                    try {
                        try { restore(file, temp, zoom, crop) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { preserve(file, temp, crop, zoom, e) }
                        catch (e: OutOfMemoryError) { preserve(file, temp, crop, zoom, e) }
                        check(temp.renameTo(encoded)) { "Falha ao confirmar JPEG privado." }
                    } finally { temp.delete() }
                }
                if (isStopped) throw CancellationException()
                val target = reserve(file, marker)
                // Only open the public row after the final JPEG (including EXIF) is durable.
                // A transient MediaStore failure retries publication, never discards the inference.
                AiPhotoFiles.copyAndPublish(app, encoded, target)
                target
            }
            val neural = encoded.isFile && runCatching { ExifInterface(encoded)
                .getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)?.contains("Real-ESRGAN") == true }.getOrDefault(false)
            val message = if (neural) "Foto restaurada" else "JPEG da câmera salvo · restauração indisponível"
            check(app.getSharedPreferences("horizoncam", Context.MODE_PRIVATE).edit()
                .putString("autoFinalUri", saved.toString()).putString("autoFinalMessage", message)
                .putFloat("autoFinalZoom", zoom).commit())
            if (file.delete()) { marker.delete(); encoded.delete() }
            AutoPhotos.post(AutoPhotos.State(false, message, saved, zoom))
            Result.success()
        } catch (e: CancellationException) { Result.retry() }
        catch (e: Exception) {
            Log.e("HorizonAuto", "Falha ao publicar; captura privada mantida", e)
            AutoPhotos.post(AutoPhotos.State(true, "Foto guardada · aguardando nova tentativa"))
            Result.retry()
        }
    }

    private fun preserve(file: File, target: File, crop: Float, zoom: Float, error: Throwable) {
        Log.w("HorizonAuto", "Restauro indisponível para ${file.name}", error)
        if (isStopped) throw CancellationException()
        if (crop <= 1f) { file.copyTo(target, overwrite = true); return }
        val framed = decodeFramed(file, crop)
        try { AiPhotoFiles.encode(app, Uri.fromFile(file), framed, target, "Zoom ${zoom}x · JPEG com recorte digital") }
        finally { framed.recycle() }
    }

    private fun restore(file: File, target: File, zoom: Float, crop: Float) {
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
                AiPhotoFiles.encode(app, Uri.fromFile(file), result.bitmap, target,
                    "Horizon AI · Real-ESRGAN general-x4v3 · textura estimada · zoom ${zoom}x")
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
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        try {
            val temp = File(marker.parentFile, "${marker.name}.tmp")
            temp.writeText(uri.toString())
            check(temp.renameTo(marker))
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
        return uri
    }
}
