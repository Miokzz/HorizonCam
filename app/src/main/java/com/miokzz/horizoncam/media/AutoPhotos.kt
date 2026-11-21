package com.miokzz.horizoncam.media

import android.content.ContentValues
import android.content.ContentUris
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

    fun capture(context: Context, capture: ImageCapture, zoom: Float, crop: Float, executor: Executor, physicalId: () -> String?, boundSensor: String?, finished: (Throwable?) -> Unit) {
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
                        val window = capture.resolutionInfo?.cropRect
                        val nativeEdge = window?.let { maxOf(it.width(), it.height()) / crop } ?: Float.MAX_VALUE
                        val power = context.getSystemService(PowerManager::class.java)
                        val cool = (power?.currentThermalStatus ?: 0) < PowerManager.THERMAL_STATUS_MODERATE
                        if (zoom >= 8f && nativeEdge <= 1280f && cool && boundSensor != null && physicalId() == boundSensor) {
                            extraFrames(capture, pending, executor, boundSensor, physicalId) {
                                enqueue(context, pending)
                                finished(null)
                            }
                            return
                        }
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

    // The reference is committed first. Extras may be dropped when time, sensor or lifecycle changes.
    private val acquiring = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun extraFrames(capture: ImageCapture, reference: File, executor: Executor, sensor: String,
                            activeSensor: () -> String?, ready: () -> Unit) {
        acquiring.add(reference.name)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var complete = false
        val finish = Runnable {
            if (!complete) { complete = true; acquiring.remove(reference.name); ready() }
        }
        handler.postDelayed(finish, 1250)
        fun next(index: Int) {
            if (complete) return
            if (index > 2 || activeSensor() != sensor) { handler.removeCallbacks(finish); finish.run(); return }
            val file = File(reference.parentFile, "${reference.nameWithoutExtension}.extra$index")
            val partial = File(file.parentFile, "${file.name}.part")
            try {
                capture.takePicture(ImageCapture.OutputFileOptions.Builder(partial).build(), executor, object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        if (complete || activeSensor() != sensor || !partial.renameTo(file)) {
                            partial.delete(); handler.removeCallbacks(finish); finish.run()
                        } else next(index + 1)
                    }
                    override fun onError(exception: ImageCaptureException) {
                        partial.delete(); handler.removeCallbacks(finish); finish.run()
                    }
                })
            } catch (e: Exception) { partial.delete(); handler.removeCallbacks(finish); finish.run() }
        }
        next(1)
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
        files.filter { it.extension == "jpg" && it.name !in acquiring }.sortedBy { it.name }.forEach { enqueue(context, it) }
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
                if (!published(target)) AiPhotoFiles.copyAndPublish(app, encoded, target)
                target
            }
            val neural = encoded.isFile && runCatching { ExifInterface(encoded)
                .getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)?.contains("Horizon AI") == true }.getOrDefault(false)
            val message = if (neural) "Foto restaurada" else "JPEG da câmera salvo · restauração indisponível"
            check(app.getSharedPreferences("horizoncam", Context.MODE_PRIVATE).edit()
                .putString("autoFinalUri", saved.toString()).putString("autoFinalMessage", message)
                .putFloat("autoFinalZoom", zoom).commit())
            if (file.delete()) { marker.delete(); encoded.delete(); extras(file).forEach { it.delete() } }
            Log.i("HorizonAuto", "published zoom=$zoom neural=$neural shutterToGalleryMs=${System.currentTimeMillis()-name.substringBefore('_').toLong()}")
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

    private fun extras(file: File) = (1..2).map { File(file.parentFile, "${file.nameWithoutExtension}.extra$it") }

    private fun fuseIfAvailable(file: File, base: Bitmap, crop: Float): Pair<Bitmap, Int> {
        val additional = extras(file).filter { it.isFile }
        if (additional.isEmpty() || maxOf(base.width, base.height) > 1280 || minOf(base.width,base.height)<16) return base to 1
        fun frame(bitmap: Bitmap, input: File): com.miokzz.horizoncam.photo.fusion.FusionFrame {
            val pixels = IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val meta = ExifInterface(input)
            val exposure = meta.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME,0.0)
            val iso = meta.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS,0)
            return com.miokzz.horizoncam.photo.fusion.FusionFrame(pixels,bitmap.width,bitmap.height,
                (1e9*exposure).toLong().takeIf { it>0 },iso.takeIf { it>0 })
        }
        val frames = mutableListOf(frame(base,file))
        for (input in additional) {
            val bitmap = decodeFramed(input,crop)
            try { if (bitmap.width==base.width && bitmap.height==base.height) frames.add(frame(bitmap,input)) }
            finally { bitmap.recycle() }
        }
        val result = com.miokzz.horizoncam.photo.fusion.BurstFusion.fuse(frames,
            com.miokzz.horizoncam.photo.fusion.FusionOptions(referenceIndex = 0),
            onProgress = { p -> AutoPhotos.post(AutoPhotos.State(true,"Alinhando · ${p.completedFrames}/${p.totalFrames}")) },
            isCancelled = { isStopped })
        if (result.acceptedFrameCount<2) return base to 1
        return Bitmap.createBitmap(result.pixels,result.width,result.height,Bitmap.Config.ARGB_8888) to result.acceptedFrameCount
    }

    private fun restore(file: File, target: File, zoom: Float, crop: Float) {
        val exif = ExifInterface(file)
        val iso = exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 100)
        val base = decodeFramed(file, crop)
        val pair = try { fuseIfAvailable(file,base,crop) }
        catch (e: CancellationException) { base.recycle(); throw e }
        catch (e: Exception) { Log.w("HorizonAuto","Fusão opcional dispensada",e); base to 1 }
        val (source, usedFrames) = pair
        if (source !== base) base.recycle()
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
            }, { isStopped || tooHot }, exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0))
            try {
                if (isStopped) throw CancellationException()
                AiPhotoFiles.encode(app, Uri.fromFile(file), result.bitmap, target,
                    "Horizon AI 2 | ${result.model} | textura estimada | zoom ${zoom}x | frames=$usedFrames")
                Log.i("HorizonAuto", "AI2 source=${source.width}x${source.height} output=${result.bitmap.width}x${result.bitmap.height} zoom=$zoom crop=$crop grid=${result.modelWidth}x${result.modelHeight} model=${result.model} backend=${result.backend} tiles=${result.tiles} frames=$usedFrames neuralMs=${result.inferenceMs} restoreMs=${result.elapsedMs} pssKiB=${android.os.Debug.getPss()} scene=${result.scene}")
            } finally { result.bitmap.recycle() }
        } finally { source.recycle() }
    }

    private fun decodeFramed(file: File, crop: Float): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            // The budget applies to the actual field of view returned by the decoder.
            // Downsampling the entire sensor first unnecessarily throws away tele detail.
            val nativeBox = com.miokzz.horizoncam.camera.StillFraming.centerCrop(w, h, crop)
            val scale = minOf(1.0, kotlin.math.sqrt(NativeRestorer.MAX_PIXELS.toDouble() /
                (nativeBox.width.toLong() * nativeBox.height)))
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
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // A process can die after insert and before the local marker rename. The UUID
        // belongs to the private capture and is stable across WorkManager retries.
        @Suppress("DEPRECATION")
        val lookup = MediaStore.setIncludePending(collection)
        val idColumn = MediaStore.MediaColumns._ID
        val existing = resolver.query(lookup, arrayOf(idColumn),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf("HC_${file.name}", "Pictures/HorizonCam/"), null)?.use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }
        val uri = existing ?: requireNotNull(resolver.insert(collection, values))
        try {
            val temp = File(marker.parentFile, "${marker.name}.tmp")
            temp.writeText(uri.toString())
            check(temp.renameTo(marker))
        } catch (e: Exception) {
            if (existing == null) resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}
