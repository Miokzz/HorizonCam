package com.miokzz.horizoncam

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in field PoC. Camera output is held in memory only. */
class LogicalPhotoSessionFieldTest {
    @Test fun probeSingleLogicalPreviewAndJpeg() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("logicalPhotoProbe") == "yes")
        assumeTrue(Build.MODEL == "SM-S9210" && Build.VERSION.SDK_INT >= 33)
        val manager = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(CameraManager::class.java)
        val chars = manager.getCameraCharacteristics("0")
        val map = checkNotNull(chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP])
        val zoomRange = chars[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]
        val previewAdvertised = map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            .any { it.width == PREVIEW_WIDTH && it.height == PREVIEW_HEIGHT }
        val jpegAdvertised = map.getOutputSizes(ImageFormat.JPEG).orEmpty()
            .any { it.width == JPEG_WIDTH && it.height == JPEG_HEIGHT }
        report("device=${Build.MODEL} camera=0 members=${chars.physicalCameraIds.sorted()} zoomRange=$zoomRange " +
            "preview=YUV:${PREVIEW_WIDTH}x$PREVIEW_HEIGHT:$previewAdvertised jpeg=${JPEG_WIDTH}x$JPEG_HEIGHT:$jpegAdvertised")
        if (!previewAdvertised || !jpegAdvertised) {
            report("status=not_attempted reason=required_output_size_not_advertised")
            return
        }

        val thread = HandlerThread("hc-logical-photo-probe").apply { start() }
        val handler = Handler(thread.looper)
        val lease = DeviceLease(handler)
        var preview: ImageReader? = null
        var jpeg: ImageReader? = null
        try {
            val device = lease.open(manager)
            preview = ImageReader.newInstance(PREVIEW_WIDTH, PREVIEW_HEIGHT, ImageFormat.YUV_420_888, 4)
            jpeg = ImageReader.newInstance(JPEG_WIDTH, JPEG_HEIGHT, ImageFormat.JPEG, 2)
            val session = ProbeSession(device, handler, preview, jpeg)
            val config = session.configuration()
            val query = runCatching { device.isSessionConfigurationSupported(config) }
            report("supportQuery=${query.getOrNull() ?: describe(query.exceptionOrNull())}")
            if (query.getOrNull() == false) {
                report("status=not_attempted reason=framework_rejected_configuration")
                return
            }
            device.createCaptureSession(config)
            session.awaitConfigured()
            report("status=configured devicesOpened=1 sessionsConfigured=1")
            for ((zoom, id, focal) in BANDS) {
                if (zoomRange == null || !zoomRange.contains(zoom)) {
                    report("zoom=$zoom status=unavailable advertisedRange=$zoomRange")
                    continue
                }
                val previewResult = session.repeat(zoom)
                val stable = previewResult.frames.lastOrNull()
                report("zoom=$zoom preview status=${if (previewResult.stable) "stable" else "partial"} " +
                    "sensor=${stable?.activeId} focalMm=${stable?.focal} ratio=${stable?.zoom} crop=${stable?.crop} " +
                    "frames=${previewResult.frames.size} sensorCounts=${previewResult.frames.groupingBy { it.activeId ?: "-" }.eachCount()} " +
                    "fps=${cadence(previewResult.frames)}")
                if (!previewResult.stable || stable == null) {
                    report("zoom=$zoom jpeg status=not_attempted reason=no_stable_preview")
                    continue
                }
                val still = session.capture(zoom)
                reportStill(zoom, id, focal, stable, still)
            }
        } catch (error: Exception) {
            report("status=failed error=${describe(error)}")
        } finally {
            // Retain the two surfaces until the real device release acknowledgement.
            // A late open or configure callback cannot create a new session here.
            val cleanup = lease.closeFirst {
                preview?.close()
                jpeg?.close()
                thread.quitSafely()
            }
            try {
                cleanup.get(4, TimeUnit.SECONDS)
                report("closeAcknowledged=true")
            } catch (error: TimeoutException) {
                report("closeAcknowledged=false readersRetainedForOnClosed=true")
                throw error
            }
        }
    }

    private fun reportStill(zoom: Float, id: String, focal: Double, preview: Reading, shot: ShotResult) {
        val meta = shot.metadata
        val bytes = shot.jpeg
        if (meta == null || bytes == null) {
            report("zoom=$zoom jpeg status=unverified reason=${shot.error ?: "result_or_timestamped_jpeg_missing"} " +
                "metadata=$meta bytes=${bytes?.size} elapsedMs=${shot.elapsedMs}")
            return
        }
        val exif = ExifInterface(bytes.inputStream())
        val focalExif = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1.0)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val correctFocal = abs(focalExif - focal) <= 0.1
        val correctSensor = preview.activeId == id && meta.activeId == id &&
            listOf(preview.focal, meta.focal).all { it != null && abs(it - focal) <= 0.1 }
        val sameZoom = listOf(preview.zoom, meta.zoom).all { it != null && abs(it - zoom) <= 0.04f }
        val sameCrop = preview.crop != null && preview.crop == meta.crop
        val sensorConfirmed = correctSensor && correctFocal && sameZoom
        val geometry = shot.grid?.let { compareLuma(it, bytes) } ?: "unavailable:no_timestamped_still_preview"
        report("zoom=$zoom jpeg status=${if (sensorConfirmed) "sensor_verified" else "sensor_mismatch"} " +
            "sensor=${meta.activeId} expected=$id ratio=${meta.zoom} focalResultMm=${meta.focal} " +
            "focalExifMm=$focalExif dimensions=${bounds.outWidth}x${bounds.outHeight} " +
            "orientation=$orientation crop=${meta.crop} sameCrop=$sameCrop matchedTimestampNs=${meta.timestamp} " +
            "jpegBytes=${bytes.size} elapsedMs=${shot.elapsedMs} lumaFit=$geometry")
    }

    private class ProbeSession(
        private val device: CameraDevice,
        private val handler: Handler,
        private val preview: ImageReader,
        private val jpeg: ImageReader
    ) {
        private val configured = CompletableFuture<CameraCaptureSession>()
        private lateinit var session: CameraCaptureSession
        @Volatile private var window: PreviewWindow? = null
        @Volatile private var shot: StillWindow? = null

        fun configuration(): SessionConfiguration {
            preview.setOnImageAvailableListener({ reader ->
                reader.acquireNextImage()?.use { image ->
                    window?.image(image.timestamp)
                    shot?.let { it.preview(image.timestamp, lumaGrid(image)) }
                }
            }, handler)
            jpeg.setOnImageAvailableListener({ reader ->
                reader.acquireNextImage()?.use { image ->
                    val current = shot ?: return@use
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    current.jpeg(image.timestamp, bytes)
                }
            }, handler)
            return SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(preview.surface, jpeg.surface).map {
                OutputConfiguration(it).apply { timestampBase = OutputConfiguration.TIMESTAMP_BASE_SENSOR }
            }, { handler.post(it) }, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) { configured.complete(session) }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    configured.completeExceptionally(IllegalStateException("HAL recusou a dupla YUV/JPEG"))
                }
            })
        }

        fun awaitConfigured() { session = configured.get(4, TimeUnit.SECONDS) }

        fun repeat(zoom: Float): PreviewResult {
            val next = PreviewWindow()
            window = next
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview.surface)
                set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
            }.build()
            session.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    next.result(read(result))
                }
            }, handler)
            val stable = next.ready.await(2_500L, TimeUnit.MILLISECONDS)
            return PreviewResult(next.matched(), stable)
        }

        fun capture(zoom: Float): ShotResult {
            val next = StillWindow()
            shot = next
            val started = SystemClock.elapsedRealtime()
            try {
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(preview.surface)
                    addTarget(jpeg.surface)
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                    set(CaptureRequest.JPEG_ORIENTATION, 0)
                    set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                }.build()
                session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        next.metadata(read(result))
                    }

                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        next.fail("capture_failure_${failure.reason}")
                    }

                    override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest,
                                                     target: android.view.Surface, frameNumber: Long) {
                        if (target === jpeg.surface) next.fail("jpeg_buffer_lost")
                    }
                }, handler)
                next.ready.await(3_000L, TimeUnit.MILLISECONDS)
                return next.snapshot(SystemClock.elapsedRealtime() - started)
            } finally {
                shot = null
            }
        }
    }

    private data class Reading(val timestamp: Long?, val activeId: String?, val zoom: Float?, val focal: Float?, val crop: String?)
    private data class PreviewResult(val frames: List<Reading>, val stable: Boolean)

    private class PreviewWindow {
        private val timestamps = mutableSetOf<Long>()
        private val results = mutableListOf<Reading>()
        val ready = CountDownLatch(1)
        @Synchronized fun image(timestamp: Long) {
            if (timestamps.size < 120) timestamps += timestamp
            checkReady()
        }
        @Synchronized fun result(frame: Reading) {
            if (results.size < 120) results += frame
            checkReady()
        }
        @Synchronized fun matched() = results.filter { it.timestamp != null && it.timestamp in timestamps }

        private fun checkReady() {
            if (ready.count == 0L || results.size < 12 || timestamps.size < 12) return
            val recent = matched()
            if (recent.size >= 12 && recent.takeLast(6).map { Triple(it.activeId, it.focal, it.zoom) }.distinct().size == 1) {
                ready.countDown()
            }
        }
    }

    private data class ShotResult(
        val metadata: Reading?, val jpeg: ByteArray?, val grid: IntArray?, val error: String?, val elapsedMs: Long
    )

    private class StillWindow {
        private val images = linkedMapOf<Long, IntArray>()
        private val jpegs = linkedMapOf<Long, ByteArray>()
        private var result: Reading? = null
        private var failure: String? = null
        val ready = CountDownLatch(1)

        @Synchronized fun jpeg(timestamp: Long, bytes: ByteArray) {
            if (jpegs.size >= 2) jpegs.remove(jpegs.keys.first())
            jpegs[timestamp] = bytes
            checkReady()
        }

        @Synchronized fun preview(timestamp: Long, grid: IntArray) {
            val sensorTimestamp = result?.timestamp
            if (sensorTimestamp == null || sensorTimestamp == timestamp) {
                if (images.size >= 12) images.remove(images.keys.first())
                images[timestamp] = grid
            }
            checkReady()
        }

        @Synchronized fun metadata(reading: Reading) { result = reading; checkReady() }
        @Synchronized fun fail(reason: String) { failure = reason; ready.countDown() }
        @Synchronized fun snapshot(elapsedMs: Long): ShotResult {
            val timestamp = result?.timestamp
            return ShotResult(result, jpegs[timestamp], images[timestamp], failure, elapsedMs)
        }

        private fun checkReady() {
            val timestamp = result?.timestamp ?: return
            if (timestamp in images && timestamp in jpegs) ready.countDown()
        }
    }

    private class DeviceLease(private val handler: Handler) {
        private val opening = CompletableFuture<CameraDevice>()
        private val closed = CompletableFuture<Unit>()
        private val current = AtomicReference<CameraDevice>()
        private val closing = AtomicBoolean()
        private var submitted = false

        @SuppressLint("MissingPermission")
        fun open(manager: CameraManager): CameraDevice {
            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    current.set(camera)
                    if (closing.get()) camera.close() else opening.complete(camera)
                }

                override fun onDisconnected(camera: CameraDevice) = onError(camera, ERROR_CAMERA_DEVICE)

                override fun onError(camera: CameraDevice, error: Int) {
                    opening.completeExceptionally(IllegalStateException("Camera2 error=$error"))
                    camera.close()
                }

                override fun onClosed(camera: CameraDevice) { closed.complete(Unit) }
            }, handler)
            submitted = true
            return opening.get(4, TimeUnit.SECONDS)
        }

        fun closeFirst(release: () -> Unit): CompletableFuture<Void> {
            closing.set(true)
            current.get()?.close()
            if (!submitted) closed.complete(Unit)
            return closed.thenRun { release() }
        }
    }

    private companion object {
        const val TAG = "HC_LOGICAL_PHOTO"
        const val PREVIEW_WIDTH = 1440
        const val PREVIEW_HEIGHT = 1080
        const val JPEG_WIDTH = 4080
        const val JPEG_HEIGHT = 3060
        const val GRID_WIDTH = 48
        const val GRID_HEIGHT = 36
        val BANDS = listOf(Triple(0.6f, "2", 2.2), Triple(1f, "5", 5.4), Triple(3f, "6", 7.0), Triple(30f, "6", 7.0))

        fun report(message: String) {
            Log.i(TAG, message)
            val line = "\n$TAG $message\n"
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", line) })
        }

        fun read(result: CaptureResult) = Reading(result[CaptureResult.SENSOR_TIMESTAMP],
            result[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID],
            result[CaptureResult.CONTROL_ZOOM_RATIO], result[CaptureResult.LENS_FOCAL_LENGTH],
            result[CaptureResult.SCALER_CROP_REGION]?.toShortString())

        fun cadence(frames: List<Reading>): String {
            val periods = frames.mapNotNull { it.timestamp }.distinct().sorted().zipWithNext { a, b -> b - a }.sorted()
            val median = periods.getOrNull(periods.size / 2) ?: return "unavailable"
            return decimal(1_000_000_000.0 / median)
        }

        fun lumaGrid(image: Image): IntArray {
            val crop = image.cropRect
            val plane = image.planes[0]
            val buffer = plane.buffer
            val start = buffer.position()
            return IntArray(GRID_WIDTH * GRID_HEIGHT) { index ->
                val x = crop.left + ((index % GRID_WIDTH + 0.5) * crop.width() / GRID_WIDTH).toInt()
                val y = crop.top + ((index / GRID_WIDTH + 0.5) * crop.height() / GRID_HEIGHT).toInt()
                buffer.get(start + y * plane.rowStride + x * plane.pixelStride).toInt() and 255
            }
        }

        fun compareLuma(grid: IntArray, jpeg: ByteArray): String {
            val previewMean = grid.average()
            val previewEnergy = grid.sumOf { (it - previewMean) * (it - previewMean) }
            if (sqrt(previewEnergy / grid.size) < 4.0) return "unavailable:low_texture"
            val options = BitmapFactory.Options().apply { inSampleSize = 8 }
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return "unavailable:jpeg_decode"
            val scales = listOf(0.90, 0.95, 1.0, 1.05, 1.10)
            val offsets = listOf(-0.02, 0.0, 0.02)
            try {
                val direct = correlation(grid, previewMean, previewEnergy, bitmap, 1.0, 0.0, 0.0)
                var best = direct
                var alignment = "1.0,0.0,0.0"
                for (scale in scales) for (dx in offsets) for (dy in offsets) {
                    val value = correlation(grid, previewMean, previewEnergy, bitmap, scale, dx, dy)
                    if (value > best + 0.001) {
                        best = value
                        alignment = "$scale,$dx,$dy"
                    }
                }
                return "directNcc=${decimal(direct)},bestNcc=${decimal(best)},scaleDxDy=$alignment"
            } finally { bitmap.recycle() }
        }

        fun correlation(grid: IntArray, mean: Double, energy: Double, bitmap: Bitmap, scale: Double, dx: Double, dy: Double): Double {
            var sum = 0.0
            var squares = 0.0
            var product = 0.0
            for (index in grid.indices) {
                val nx = ((index % GRID_WIDTH + 0.5) / GRID_WIDTH - 0.5) * scale + 0.5 + dx
                val ny = ((index / GRID_WIDTH + 0.5) / GRID_HEIGHT - 0.5) * scale + 0.5 + dy
                val x = (nx * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                val y = (ny * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                val pixel = bitmap.getPixel(x, y)
                val luma = ((pixel shr 16 and 255) * 0.299 + (pixel shr 8 and 255) * 0.587 + (pixel and 255) * 0.114)
                sum += luma
                squares += luma * luma
                product += (grid[index] - mean) * luma
            }
            val variance = squares - sum * sum / grid.size
            return if (variance > 0) product / sqrt(energy * variance) else 0.0
        }

        fun decimal(value: Double) = String.format(Locale.ROOT, "%.3f", value)
        fun describe(error: Throwable?): String {
            val root = error?.cause ?: error ?: return "unreported"
            return "${root.javaClass.simpleName}:${root.message.orEmpty().replace('\n', ' ')}"
        }
    }
}
