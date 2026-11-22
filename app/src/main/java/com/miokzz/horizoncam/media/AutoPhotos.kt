package com.miokzz.horizoncam.media

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
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
import com.miokzz.horizoncam.ai.PhotoProvenance
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

    /** Reserve a private reference shared by night/fusion and the durable publication queue. */
    fun beginBurst(context: Context, zoom: Float): File {
        val dir = directory(context)
        check(dir.isDirectory || dir.mkdirs()) { "Armazenamento da foto indisponível." }
        val file = File(dir, "${System.currentTimeMillis()}_${zoom.toRawBits()}_${1f.toRawBits()}_${UUID.randomUUID()}.jpg")
        acquiring.add(file.name)
        return file
    }

    internal fun encodedFor(source: File) = File(source.parentFile, "${source.nameWithoutExtension}.result")

    fun releaseBurst(context: Context, reference: File, useCaptured: Boolean = false) {
        if (useCaptured && reference.isFile) runCatching { capturedFlag(reference).createNewFile() }
        acquiring.remove(reference.name)
        if (reference.isFile) enqueue(context, reference)
    }

    internal fun capturedFlag(source: File) = File(source.parentFile, "${source.nameWithoutExtension}.captured")
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
            .setInputData(Data.Builder().putString("source", file.name)
                .putLong("enqueuedAtMs", System.currentTimeMillis()).build()).build()
        // Workers drain in capture order. Recovery may append an already-completed source;
        // its private file is removed only after durable publication, making that entry a no-op.
        WorkManager.getInstance(context).enqueueUniqueWork("horizon-photo-queue", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

class AutoPhotoWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    private val app = applicationContext
    private val resolver = app.contentResolver
    private val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    override fun doWork(): Result {
        val name = inputData.getString("source") ?: return Result.success()
        if (!name.matches(Regex("[0-9]+_[0-9]+_(?:[0-9]+_)?[0-9a-f-]{36}\\.jpg"))) return Result.success()
        val file = File(AutoPhotos.directory(app), name)
        if (!file.isFile) return Result.success()
        val zoom = Float.fromBits(name.substringAfter('_').substringBefore('_').toInt())
        val parts = name.split('_')
        val crop = if (parts.size == 4) Float.fromBits(parts[2].toInt()) else 1f
        val marker = File(file.parentFile, "${file.nameWithoutExtension}.uri")
        val shutterAt = name.substringBefore('_').toLong()
        val queuedAt = inputData.getLong("enqueuedAtMs", 0L)
        val workerAt = System.currentTimeMillis()
        val referenceWrittenAt = file.lastModified()
        return try {
            val encoded = AutoPhotos.encodedFor(file)
            val previous = findReservation(file, marker)
            var publishAt = SystemClock.elapsedRealtime()
            val saved = if (previous != null && published(previous)) previous else {
                if (!encoded.isFile) {
                    val useCaptured = AutoPhotos.capturedFlag(file).isFile
                    val thermal = app.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0
                    if (!useCaptured && thermal >= PowerManager.THERMAL_STATUS_SEVERE) {
                        AutoPhotos.post(AutoPhotos.State(true, "Restauração aguardando resfriamento"))
                        return Result.retry()
                    }
                    val temp = File(file.parentFile, "${file.nameWithoutExtension}.rendering")
                    try {
                        try {
                            if (useCaptured) file.copyTo(temp, overwrite = true)
                            else restore(file, temp, zoom, crop)
                        }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { preserve(file, temp, crop, zoom, e) }
                        catch (e: OutOfMemoryError) { preserve(file, temp, crop, zoom, e) }
                        check(temp.renameTo(encoded)) { "Falha ao confirmar JPEG privado." }
                    } finally { temp.delete() }
                }
                if (isStopped) throw CancellationException()
                publishAt = SystemClock.elapsedRealtime()
                val target = previous ?: reserve(file, marker)
                // Only open the public row after the final JPEG (including EXIF) is durable.
                // A transient MediaStore failure retries publication, never discards the inference.
                AiPhotoFiles.copyAndPublish(app, encoded, target)
                target
            }
            val description = runCatching {
                if (encoded.isFile) ExifInterface(encoded).getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
                else resolver.openInputStream(saved)?.use { ExifInterface(it).getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION) }
            }.getOrNull().orEmpty()
            val provenance = PhotoProvenance.classify(description)
            val message = when (provenance) {
                PhotoProvenance.Kind.NEURAL -> "Foto restaurada"
                PhotoProvenance.Kind.RESIZED -> "Foto salva · ampliação convencional"
                PhotoProvenance.Kind.FUSION -> "Fusão salva · sem restauração neural"
                PhotoProvenance.Kind.NATIVE -> "Foto salva · detalhe nativo preservado"
                PhotoProvenance.Kind.CAMERA -> "JPEG da câmera salvo · sem restauração neural"
            }
            check(app.getSharedPreferences("horizoncam", Context.MODE_PRIVATE).edit()
                .putString("autoFinalUri", saved.toString()).putString("autoFinalMessage", message)
                .putFloat("autoFinalZoom", zoom).commit())
            if (file.delete()) {
                marker.delete()
                encoded.delete()
                AutoPhotos.capturedFlag(file).delete()
                extras(file).forEach { it.delete() }
            }
            Log.i("HorizonAuto", "published zoom=$zoom neural=${provenance == PhotoProvenance.Kind.NEURAL} " +
                "provenance=$provenance shutterToGalleryMs=${System.currentTimeMillis() - shutterAt} " +
                "referenceMs=${(referenceWrittenAt - shutterAt).takeIf { it >= 0 }} " +
                "acquireAndEnqueueMs=${(queuedAt - shutterAt).takeIf { it >= 0 }} " +
                "queueMs=${(workerAt - queuedAt).takeIf { queuedAt > 0 && it >= 0 }} " +
                "publishMs=${SystemClock.elapsedRealtime() - publishAt}")
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
        val source = decodeFramed(file, crop).bitmap
        try {
            val scale = com.miokzz.horizoncam.camera.StillFraming.reconstructionScale(
                source.width, source.height, crop, NativeRestorer.MAX_PIXELS)
            val resized = if (scale > 1) {
                try { Bitmap.createScaledBitmap(source, source.width * scale, source.height * scale, true) }
                catch (e: OutOfMemoryError) {
                    Log.w("HorizonAuto", "Memória insuficiente para ampliação convencional", e)
                    source
                }
            } else source
            try {
                if (isStopped) throw CancellationException()
                val description = PhotoProvenance.describe(null, 1, zoom,
                    source.width, source.height, resized.width, resized.height, conventionalResize = resized !== source)
                AiPhotoFiles.encode(app, Uri.fromFile(file), resized, target, description)
            } finally { if (resized !== source) resized.recycle() }
        } finally { source.recycle() }
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
            val bitmap = decodeFramed(input,crop).bitmap
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
        val started = SystemClock.elapsedRealtime()
        val exif = ExifInterface(file)
        val iso = exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0)
        val framed = decodeFramed(file, crop)
        val decodedAt = SystemClock.elapsedRealtime()
        val base = framed.bitmap
        val pair = try { fuseIfAvailable(file,base,crop) }
        catch (e: CancellationException) { base.recycle(); throw e }
        catch (e: Exception) { Log.w("HorizonAuto","Fusão opcional dispensada",e); base to 1 }
        catch (e: OutOfMemoryError) { Log.w("HorizonAuto","Memória insuficiente para fusão opcional",e); base to 1 }
        val (source, usedFrames) = pair
        val fusedAt = SystemClock.elapsedRealtime()
        if (source !== base) base.recycle()
        try {
            var lastPercent = -1
            val power = app.getSystemService(PowerManager::class.java)
            var tooHot = false
            val upscale = com.miokzz.horizoncam.camera.StillFraming.reconstructionScale(source.width, source.height, crop, NativeRestorer.MAX_PIXELS)
            NativeRestorer.restore(app, source, zoom, iso, upscale, { progress ->
                val percent = 100 * progress.completedTiles / progress.totalTiles
                if (lastPercent != percent) {
                    AutoPhotos.post(AutoPhotos.State(true, "Revelando foto · $percent%"))
                    lastPercent = percent
                    tooHot = (power?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE
                    if (tooHot) AutoPhotos.post(AutoPhotos.State(true, "Restauração aguardando resfriamento"))
                }
            }, { isStopped || tooHot }, exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)).use { result ->
                if (isStopped) throw CancellationException()
                val output = result.bitmap
                val description = PhotoProvenance.describe(result.model.takeIf { result.usedNeuralModel }, usedFrames,
                    zoom, source.width, source.height, output.width, output.height)
                val copyOriginal = !result.usedNeuralModel && source === base && framed.isFullImage
                val writeStart = System.nanoTime()
                if (copyOriginal) {
                    // Retain the camera's compressed scan, dimensions and orientation.
                    file.copyTo(target, overwrite = true)
                    ExifInterface(target).apply {
                        setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description)
                        saveAttributes()
                    }
                } else {
                    AiPhotoFiles.encode(app, Uri.fromFile(file), output, target, description)
                }
                val encodeMs = (System.nanoTime() - writeStart) / 1_000_000
                Log.i("HorizonAuto", "render capture=${file.name} source=${source.width}x${source.height} " +
                    "output=${output.width}x${output.height} zoom=$zoom crop=$crop " +
                    "grid=${result.modelWidth}x${result.modelHeight} model=${result.model} backend=${result.backend} " +
                    "tiles=${result.tiles} frames=$usedFrames originalScan=$copyOriginal " +
                    "invokeMs=${result.nativeInferenceMs} neuralMs=${result.inferenceMs} restoreMs=${result.elapsedMs} " +
                    "decodeMs=${decodedAt - started} fusionMs=${fusedAt - decodedAt} " +
                    "encodeMs=$encodeMs pssKiB=${android.os.Debug.getPss()} scene=${result.scene}")
            }
        } finally { source.recycle() }
    }

    private data class FramedImage(val bitmap: Bitmap, val isFullImage: Boolean)

    private fun decodeFramed(file: File, crop: Float): FramedImage {
        var fullImage = false
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
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
            fullImage = outW == w && outH == h && box.width == w && box.height == h
            decoder.crop = android.graphics.Rect(box.left, box.top, box.left + box.width, box.top + box.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        }
        return FramedImage(bitmap, fullImage)
    }

    private fun pendingStatus(uri: Uri): Int? = checkNotNull(resolver.query(uri,
        arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else null
    }

    private fun published(uri: Uri) = pendingStatus(uri) == 0

    private fun findReservation(file: File, marker: File): Uri? {
        if (marker.isFile) {
            val uri = Uri.parse(marker.readText())
            if (pendingStatus(uri) != null) return uri
        }
        // A process can die after insert and before the local marker rename. The UUID
        // belongs to the private capture and is stable across WorkManager retries.
        @Suppress("DEPRECATION")
        val lookup = MediaStore.setIncludePending(images)
        val idColumn = MediaStore.MediaColumns._ID
        return checkNotNull(resolver.query(lookup, arrayOf(idColumn),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf("HC_${file.name}", "Pictures/HorizonCam/"), null)).use { cursor ->
            if (cursor.moveToFirst()) ContentUris.withAppendedId(images, cursor.getLong(0)) else null
        }
    }

    private fun reserve(file: File, marker: File): Uri {
        val values = CaptureLibrary.values(photo = true).apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "HC_${file.name}")
            put(MediaStore.Images.Media.DATE_TAKEN, file.name.substringBefore('_').toLong())
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(images, values))
        // If recording the URI fails, the pending row remains discoverable by capture UUID.
        val temp = File(marker.parentFile, "${marker.name}.tmp")
        temp.writeText(uri.toString())
        check(temp.renameTo(marker))
        return uri
    }
}
