package com.miokzz.horizoncam

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.miokzz.horizoncam.ai.NativeRestorer
import com.miokzz.horizoncam.ai.PhotoProvenance
import com.miokzz.horizoncam.camera.StillFraming
import com.miokzz.horizoncam.media.AutoPhotos
import com.miokzz.horizoncam.photo.fusion.BurstFusion
import com.miokzz.horizoncam.photo.fusion.FusionFrame
import com.miokzz.horizoncam.photo.fusion.FusionOptions
import com.miokzz.horizoncam.photo.fusion.FusionResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/** Replays consented private JPEGs without opening the camera. Only the publication test writes to MediaStore. */
class PhotoFusionPerformanceTest {
    @Test
    fun replaySamePrivateBurst() {
        val runner = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Replay da fusão requer -e fusionPerf yes.", args.getString("fusionPerf") == "yes")
        val fixture = safeName(args.getString("fusionFixture"), "fusionFixture")
        val run = safeName(args.getString("fusionRun"), "fusionRun")
        val zoom = requireNotNull(args.getString("zoom")?.toFloatOrNull()).also { require(it.isFinite() && it >= 1f) }
        val crop = requireNotNull(args.getString("crop")?.toFloatOrNull()).also { require(it.isFinite() && it >= 1f) }
        val repetitions = (args.getString("reps")?.toIntOrNull() ?: 10).also { require(it in 3..30) }
        val context = runner.targetContext
        val directory = File(context.filesDir, "fusion-perf/" + fixture)
        val reportFile = File(directory, "report-" + run + ".json")
        require(directory.isDirectory) { "Prepare a pasta privada fusion-perf/" + fixture }
        val inputs = listOf("reference.jpg", "extra1.jpg", "extra2.jpg").map { File(directory, it) }
        require(inputs.all { it.isFile && it.length() > 0L }) { "O replay requer a sequência privada completa." }
        val before = snapshot(context)
        val decodeStart = System.nanoTime()
        val frames = inputs.map { decode(it, crop) }
        val decodeMs = ms(System.nanoTime() - decodeStart)
        val base = frames.first()
        require(frames.all { it.width == base.width && it.height == base.height })
        val inputEvidence = inputs.zip(frames).map { (file, frame) ->
            JSONObject().put("name", file.name).put("sha256", fileHash(file)).put("pixels", pixelHash(frame.pixels))
                .put("exposureNs", frame.exposureTimeNs ?: JSONObject.NULL).put("iso", frame.iso ?: JSONObject.NULL)
        }
        val options = FusionOptions(referenceIndex = 0, protectReferenceDetail = zoom > 10f)
        val measurements = JSONArray()
        var cold: JSONObject? = null
        var expectedPixels: String? = null
        var expectedFusion: String? = null
        val warmMs = mutableListOf<Double>()
        repeat(repetitions + 1) { index ->
            val started = System.nanoTime()
            var preparedAt = started
            var registeredAt = started
            val result = BurstFusion.fuse(frames, options, onProgress = { progress ->
                if (progress.completedRows == 0) {
                    if (progress.completedFrames == 0) preparedAt = System.nanoTime()
                    if (progress.completedFrames == frames.size) registeredAt = System.nanoTime()
                }
            })
            val finished = System.nanoTime()
            val elapsed = ms(finished - started)
            val diagnostics = diagnostics(result)
            val pixels = pixelHash(result.pixels)
            if (index == 0) {
                expectedPixels = pixels
                expectedFusion = diagnostics.toString()
            } else {
                assertEquals("Raster variável no mesmo burst", expectedPixels, pixels)
                assertEquals("Diagnóstico variável no mesmo burst", expectedFusion, diagnostics.toString())
            }
            val sample = JSONObject().put("fusionMs", elapsed).put("prepareMs", ms(preparedAt - started))
                .put("alignAndMaskMs", ms(registeredAt - preparedAt)).put("renderMs", ms(finished - registeredAt))
                .put("system", snapshot(context))
            if (index == 0) cold = sample else {
                measurements.put(sample)
                warmMs += elapsed
            }
        }
        val warmP50 = percentile(warmMs, 0.50)
        val warmP95 = percentile(warmMs, 0.95)
        val report = JSONObject().put("fixture", fixture).put("run", run).put("zoom", zoom.toDouble())
            .put("crop", crop.toDouble()).put("decodeMs", decodeMs).put("width", base.width).put("height", base.height)
            .put("inputs", JSONArray(inputEvidence)).put("cold", checkNotNull(cold))
            .put("warmRuns", measurements).put("warmP50Ms", warmP50).put("warmP95Ms", warmP95)
            .put("outputSha256", expectedPixels).put("fusion", JSONObject(checkNotNull(expectedFusion)))
            .put("before", before).put("after", snapshot(context))
        check(reportFile.createNewFile()) { "Use outro identificador de execução para preservar o relatório." }
        reportFile.writeText(report.toString(2))
        Log.i("HorizonFusionPerf", "fixture=" + fixture + " run=" + run + " decodeMs=" + decodeMs +
            " coldMs=" + cold?.optDouble("fusionMs") + " warmP50Ms=" + warmP50 + " warmP95Ms=" + warmP95 +
            " outputSha256=" + expectedPixels)
    }

    @Test
    fun publishSamePrivateBurst() {
        val runner = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Replay da publicação requer -e fusionPublish yes.", args.getString("fusionPublish") == "yes")
        val fixture = safeName(args.getString("fusionFixture"), "fusionFixture")
        val run = safeName(args.getString("fusionRun"), "fusionRun")
        val zoom = requireNotNull(args.getString("zoom")?.toFloatOrNull()).also { require(it.isFinite() && it >= 8f) }
        val crop = requireNotNull(args.getString("crop")?.toFloatOrNull()).also { require(it.isFinite() && it >= 1f) }
        val expectedFrames = (args.getString("expectedFrames")?.toIntOrNull() ?: 3).also { require(it in 1..3) }
        val context = runner.targetContext
        val directory = File(context.filesDir, "fusion-perf/" + fixture)
        val inputs = listOf("reference.jpg", "extra1.jpg", "extra2.jpg").map { File(directory, it) }
        require(inputs.all { it.isFile && it.length() > 0L }) { "O replay requer a sequência privada completa." }
        val output = File(directory, "publication-" + run)
        check(output.mkdir()) { "Use outro identificador de execução para preservar os resultados." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(inputs.first().absolutePath, bounds)
        val nativeEdge = maxOf(bounds.outWidth, bounds.outHeight)
        require(nativeEdge > 0 && nativeEdge / crop <= 1280f) { "A sequência precisa ser elegível para fusão." }
        val inputEvidence = JSONArray(inputs.map { f -> JSONObject().put("name", f.name).put("sha256", fileHash(f)) })
        val requested = AtomicInteger()
        val firstPart = AtomicReference<File>()
        val completion = CompletableFuture<Throwable?>()
        val resolver = context.contentResolver
        val photos = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val preferences = context.getSharedPreferences("horizoncam", Context.MODE_PRIVATE)
        val priorUri = preferences.getString("autoFinalUri", null)
        val priorMessage = preferences.getString("autoFinalMessage", null)
        val priorZoom = preferences.all["autoFinalZoom"] as? Float
        val before = snapshot(context)
        var published: Uri? = null
        try {
            val started = SystemClock.elapsedRealtimeNanos()
            // Supply only the consented triplet when the real production queue requests it.
            AutoPhotos.capture(context, zoom, crop, nativeEdge, takePicture = { file, complete ->
                val index = requested.getAndIncrement()
                if (index == 0) firstPart.set(file)
                complete(runCatching {
                    inputs[index].copyTo(file)
                    Unit
                })
            }, physicalId = { "6" }, boundSensor = "6") { error -> completion.complete(error) }
            assertNull(completion.get(20, TimeUnit.SECONDS))
            val queuedAt = SystemClock.elapsedRealtimeNanos()
            val part = checkNotNull(firstPart.get())
            val source = File(part.parentFile, part.nameWithoutExtension + ".jpg")
            val deadline = SystemClock.elapsedRealtime() + 90_000
            while (source.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(30)
            val observedAt = SystemClock.elapsedRealtimeNanos()
            assertFalse("O worker não completou esta publicação de replay.", source.exists())
            val uri = checkPublishedReplay(context, photos, "HC_" + source.name)
            published = uri
            assertEquals("A fila deve solicitar os três quadros de entrada.", inputs.size, requested.get())
            val finalJpeg = File(output, "final.jpg")
            checkNotNull(resolver.openInputStream(uri)).use { input ->
                finalJpeg.outputStream().use { target -> input.copyTo(target) }
            }
            val exif = ExifInterface(finalJpeg)
            val description = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION).orEmpty()
            assertEquals(PhotoProvenance.Kind.NEURAL, PhotoProvenance.classify(description))
            val model = description.split('|')[1].trim()
            val retainedFrames = Regex("""frames=(\d+)""").find(description)?.groupValues?.get(1)?.toIntOrNull()
            assertEquals("Número de quadros no JPEG publicado.", expectedFrames, retainedFrames)
            val captureExif = ExifInterface(inputs.first())
            val compareTags = listOf(
                ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH, ExifInterface.TAG_ISO_SPEED_RATINGS,
            )
            val rationalTags = setOf(ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER)
            val preserved = JSONObject()
            for (tag in compareTags) {
                val expected = captureExif.getAttribute(tag)
                val actual = exif.getAttribute(tag)
                if (expected != null && actual != null && tag in rationalTags) {
                    val actualValue = exif.getAttributeDouble(tag, Double.NaN)
                    assertTrue(actualValue.isFinite())
                    val originalValue = captureExif.getAttributeDouble(tag, Double.NaN)
                    // The existing JPEG metadata writer rounds exposure to 1/10000 s.
                    // Keep both values so the before/after comparison can require identical output.
                    val tolerance = if (tag == ExifInterface.TAG_EXPOSURE_TIME) 0.0001 else 0.0
                    assertEquals("EXIF " + tag, originalValue, actualValue, tolerance)
                    if (tag == ExifInterface.TAG_EXPOSURE_TIME) preserved.put("captureExposureSeconds", originalValue)
                    preserved.put(tag, actualValue)
                } else {
                    assertEquals("EXIF " + tag, expected, actual)
                    preserved.put(tag, actual ?: JSONObject.NULL)
                }
            }
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
            assertEquals(ExifInterface.ORIENTATION_NORMAL, orientation)
            val raster = decode(finalJpeg, 1f)
            val report = JSONObject().put("fixture", fixture).put("run", run).put("inputs", inputEvidence)
                .put("zoom", zoom.toDouble()).put("crop", crop.toDouble()).put("model", model)
                .put("frames", retainedFrames).put("description", description).put("orientation", orientation)
                .put("exif", preserved).put("requestedFrames", requested.get()).put("publishedRows", 1)
                .put("width", raster.width).put("height", raster.height).put("jpegSha256", fileHash(finalJpeg))
                .put("rasterSha256", pixelHash(raster.pixels)).put("replayFilesAndEnqueueMs", ms(queuedAt - started))
                .put("replayToPublicationObservedMs", ms(observedAt - started))
                .put("before", before).put("after", snapshot(context))
            File(output, "report.json").writeText(report.toString(2))
            Log.i("HorizonFusionPerf", "publication fixture=" + fixture + " run=" + run +
                " model=" + model + " rasterSha256=" + report.getString("rasterSha256"))
        } finally {
            published?.let { uri ->
                // Only the row identified by this capture UUID is eligible for cleanup.
                resolver.delete(uri, null, null)
                if (preferences.getString("autoFinalUri", null) == uri.toString()) {
                    val edit = preferences.edit().putString("autoFinalUri", priorUri).putString("autoFinalMessage", priorMessage)
                    if (priorZoom == null) edit.remove("autoFinalZoom") else edit.putFloat("autoFinalZoom", priorZoom)
                    check(edit.commit())
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun checkPublishedReplay(context: Context, photos: Uri, name: String): Uri {
        val columns = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.MIME_TYPE,
        )
        val filter = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val values = arrayOf(name, "Pictures/HorizonCam/")
        return checkNotNull(context.contentResolver.query(MediaStore.setIncludePending(photos), columns, filter, values, null))
            .use { cursor ->
                assertEquals("A mídia de replay deve ter exatamente uma linha, incluindo pendentes.", 1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(1))
                assertEquals("image/jpeg", cursor.getString(2))
                ContentUris.withAppendedId(photos, cursor.getLong(0))
            }
    }

    private fun safeName(value: String?, arg: String): String {
        require(value != null && value.matches(Regex("[a-zA-Z0-9_-]{1,48}"))) { "Argumento inválido " + arg }
        return value
    }

    private fun decode(file: File, crop: Float): FusionFrame {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val nativeBox = StillFraming.centerCrop(width, height, crop)
            val scale = minOf(1.0, sqrt(NativeRestorer.MAX_PIXELS.toDouble() /
                (nativeBox.width.toLong() * nativeBox.height)))
            val outWidth = (width * scale).toInt().coerceAtLeast(1)
            val outHeight = (height * scale).toInt().coerceAtLeast(1)
            decoder.setTargetSize(outWidth, outHeight)
            val box = StillFraming.centerCrop(outWidth, outHeight, crop)
            decoder.crop = Rect(box.left, box.top, box.left + box.width, box.top + box.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val exif = ExifInterface(file)
            val exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
            val iso = exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0)
            FusionFrame(pixels, width, height, (1e9 * exposure).toLong().takeIf { it > 0 }, iso.takeIf { it > 0 })
        } finally {
            bitmap.recycle()
        }
    }

    private fun diagnostics(result: FusionResult): JSONObject {
        val alignments = JSONArray()
        result.alignments.forEach { alignment ->
            alignments.put(JSONObject().put("frameIndex", alignment.frameIndex)
                .put("dx", alignment.dx).put("dy", alignment.dy).put("confidence", alignment.confidence)
                .put("exposureGain", alignment.exposureGain).put("sharpnessRatio", alignment.sharpnessRatio)
                .put("accepted", alignment.accepted).put("reason", alignment.reason ?: JSONObject.NULL)
                .put("reliable", alignment.subpixelReliable)
                .put("relativeExposure", alignment.relativeExposure ?: JSONObject.NULL)
                .put("motionRejectedFraction", alignment.motionRejectedFraction.toDouble()))
        }
        return JSONObject().put("width", result.width).put("height", result.height)
            .put("referenceIndex", result.referenceIndex).put("acceptedFrames", result.acceptedFrameCount)
            .put("superResolved", result.superResolved).put("phaseCoverage", result.phaseCoverage.toDouble())
            .put("measuredSampleFraction", result.measuredSampleFraction.toDouble())
            .put("highlightRecoveredPixels", result.highlightRecoveredPixels).put("alignments", alignments)
    }

    private fun fileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val data = ByteArray(16 * 1024)
            while (true) {
                val count = stream.read(data)
                if (count < 0) break
                digest.update(data, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun pixelHash(pixels: IntArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(16 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        for (pixel in pixels) {
            if (buffer.remaining() < Int.SIZE_BYTES) {
                digest.update(buffer.array(), 0, buffer.position())
                buffer.clear()
            }
            buffer.putInt(pixel)
        }
        digest.update(buffer.array(), 0, buffer.position())
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun ms(nanos: Long) = nanos / 1e6

    private fun percentile(samples: List<Double>, fraction: Double): Double {
        val sorted = samples.sorted()
        val position = fraction * (sorted.size - 1)
        val lower = position.toInt()
        val upper = minOf(lower + 1, sorted.lastIndex)
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
    }

    private fun snapshot(context: Context): JSONObject {
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeUnless { it == Int.MIN_VALUE }?.div(10.0)
        val rss = runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.filter { it.startsWith("VmRSS:") || it.startsWith("VmHWM:") }
                    .associate { line -> line.substringBefore(':') to Regex("\\d+").find(line)?.value?.toLongOrNull() }
            }
        }.getOrDefault(emptyMap())
        return JSONObject().put("pssKiB", Debug.getPss()).put("rssKiB", rss["VmRSS"] ?: JSONObject.NULL)
            .put("peakRssKiB", rss["VmHWM"] ?: JSONObject.NULL)
            .put("thermalStatus", power?.currentThermalStatus ?: JSONObject.NULL)
            .put("interactive", power?.isInteractive ?: JSONObject.NULL).put("batteryTempC", battery ?: JSONObject.NULL)
    }
}
