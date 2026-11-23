package com.miokzz.horizoncam

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
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
import androidx.annotation.RequiresApi
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Tests paired tele RAW and JPEG. Optional calibration pairs remain in the field app's private cache. */
class TeleRawPairFieldTest {
    @Test fun capturePairedTeleOutputs() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("rawPair") == "yes")
        assumeTrue(Build.MODEL == "SM-S9210")
        if (Build.VERSION.SDK_INT < 33) {
            assumeTrue("O ensaio precisa de timestamps de sensor por saída.", false)
            return
        }
        val runner = InstrumentationRegistry.getInstrumentation()
        val manager = runner.targetContext.getSystemService(CameraManager::class.java)
        fun report(line: String) = runner.sendStatus(0, Bundle().apply {
            putString("stream", "\nHC_RAW_PAIR $line\n")
        })
        val cache = runner.targetContext.cacheDir.takeIf { args.getString("retainPair") == "yes" }
        assertTrue("Nenhuma sessão entregou um par RAW/JPEG confirmado da teleobjetiva.",
            attempt(manager, true, cache, ::report) || attempt(manager, false, cache, ::report))
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(33)
    private fun attempt(manager: CameraManager, withPreview: Boolean, cache: File?, report: (String) -> Unit): Boolean {
        val thread = HandlerThread("hc-paired-tele").apply { start() }
        val handler = Handler(thread.looper)
        val opened = CompletableFuture<CameraDevice>()
        val closed = CompletableFuture<Unit>()
        val raw = CompletableFuture<Image>()
        val jpeg = CompletableFuture<Jpeg>()
        val readers = mutableListOf<ImageReader>()
        var submitted = false
        try {
            manager.openCamera("0", object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { if (!opened.complete(camera)) camera.close() }
                override fun onClosed(camera: CameraDevice) { closed.complete(Unit) }
                override fun onDisconnected(camera: CameraDevice) = onError(camera, ERROR_CAMERA_DEVICE)
                override fun onError(camera: CameraDevice, error: Int) {
                    val failure = IllegalStateException("device_error_$error")
                    opened.completeExceptionally(failure)
                    raw.completeExceptionally(failure)
                    jpeg.completeExceptionally(failure)
                    camera.close()
                }
            }, handler)
            submitted = true
            val camera = opened.get(4, TimeUnit.SECONDS)
            val rawReader = ImageReader.newInstance(3648, 2736, ImageFormat.RAW_SENSOR, 2).also(readers::add)
            val jpegReader = ImageReader.newInstance(3648, 2736, ImageFormat.JPEG, 2).also(readers::add)
            val preview = if (withPreview) ImageReader.newInstance(1440, 1080, ImageFormat.PRIVATE, 4,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE).also(readers::add) else null
            val previewReady = CompletableFuture<Unit>()
            var frames = 0
            preview?.setOnImageAvailableListener({ reader -> reader.acquireNextImage()?.use {
                if (++frames >= 6) previewReady.complete(Unit)
            } }, handler)
            rawReader.setOnImageAvailableListener({ reader -> reader.acquireNextImage()?.let { image ->
                if (!raw.complete(image)) image.close()
            } }, handler)
            jpegReader.setOnImageAvailableListener({ reader -> reader.acquireNextImage()?.use { image ->
                runCatching {
                    val buffer = image.planes.single().buffer
                    val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                    val exif = ExifInterface(bytes.inputStream())
                    val focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1.0)
                    val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                        .takeIf { it in 1..8 } ?: ExifInterface.ORIENTATION_NORMAL
                    val iso = exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0).takeIf { it > 0 }
                    val exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0.0 }
                    Jpeg(image.timestamp, image.width, image.height, focal, orientation, iso, exposure,
                        bytes.takeIf { cache != null })
                }.fold(jpeg::complete, jpeg::completeExceptionally)
            } }, handler)

            val configured = CompletableFuture<CameraCaptureSession>()
            val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, readers.map { reader ->
                OutputConfiguration(reader.surface).apply {
                    setPhysicalCameraId("6")
                    timestampBase = OutputConfiguration.TIMESTAMP_BASE_SENSOR
                }
            }, { handler.post(it) }, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) { configured.complete(session) }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    configured.completeExceptionally(IllegalStateException("configuration_failed"))
                }
            })
            val support = runCatching { camera.isSessionConfigurationSupported(config) }.getOrNull()
            report("preview=$withPreview query=$support")
            if (support == false) return false
            camera.createCaptureSession(config)
            val session = configured.get(4, TimeUnit.SECONDS)
            if (preview != null) {
                val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    .apply { addTarget(preview.surface) }.build()
                session.setRepeatingRequest(previewRequest, null, handler)
                previewReady.get(3, TimeUnit.SECONDS)
            }
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(rawReader.surface)
                addTarget(jpegReader.surface)
                preview?.let { addTarget(it.surface) }
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            }.build()
            val meta = CompletableFuture<CaptureMetadata>()
            val started = SystemClock.elapsedRealtime()
            session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    result.physicalCameraTotalResults["6"]?.let {
                        meta.complete(CaptureMetadata(result, it))
                    }
                    if (!meta.isDone) meta.completeExceptionally(IllegalStateException("missing_tele_result"))
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    meta.completeExceptionally(IllegalStateException("capture_failed_${failure.reason}"))
                }
            }, handler)
            val metadata = meta.get(3, TimeUnit.SECONDS)
            val physical = metadata.physical
            val still = jpeg.get(4, TimeUnit.SECONDS)
            val image = raw.get(3, TimeUnit.SECONDS)
            val acquisitionMs = SystemClock.elapsedRealtime() - started
            val exposureStart = physical[CaptureResult.SENSOR_TIMESTAMP]
            val focal = physical[CaptureResult.LENS_FOCAL_LENGTH]
            val iso = physical[CaptureResult.SENSOR_SENSITIVITY]
            val exposure = physical[CaptureResult.SENSOR_EXPOSURE_TIME]
            val verified = exposureStart != null && exposureStart > 0L &&
                exposureStart == image.timestamp && exposureStart == still.timestamp &&
                focal != null && abs(focal - 7f) <= 0.05f && abs(still.focalMm - 7.0) <= 0.05 &&
                iso != null && iso > 0 && exposure != null && exposure > 0L &&
                image.width == still.width && image.height == still.height && image.format == ImageFormat.RAW_SENSOR
            if (!verified) {
                report("preview=$withPreview status=unverified rawTimestamp=${image.timestamp} " +
                    "jpegTimestamp=${still.timestamp} resultTimestamp=$exposureStart focalRaw=$focal focalJpeg=${still.focalMm}")
                return false
            }
            val info = manager.getCameraCharacteristics("6")
            val bundle = if (cache == null) null else retain(cache, info, metadata, request,
                image, still, checkNotNull(focal), checkNotNull(iso), checkNotNull(exposure), withPreview, acquisitionMs)
            val dngBytes = bundle?.dngBytes ?: ByteCount().also { output ->
                DngCreator(info, physical).use { it.setOrientation(still.orientation).writeImage(output, image) }
            }.bytes
            val dngVerified = dngBytes >= 2L * image.width * image.height
            if (bundle != null && dngVerified) {
                report("privateCalibration=${bundle.id}")
            }
            report("preview=$withPreview status=${if (dngVerified) "verified" else "unverified"} " +
                "raw=${image.width}x${image.height} jpeg=${still.width}x${still.height} focalRaw=$focal " +
                "focalJpeg=${still.focalMm} equalTimestamps=true timestampNs=$exposureStart " +
                "exposureNs=$exposure iso=$iso rawAndJpegMs=$acquisitionMs dngBytes=$dngBytes")
            return dngVerified
        } catch (error: Exception) {
            report("preview=$withPreview status=failed reason=${error.cause?.message ?: error.javaClass.simpleName}")
            return false
        } finally {
            raw.thenAccept { it.close() }
            raw.cancel(false)
            opened.thenAccept { it.close() }
            opened.cancel(false)
            if (!submitted) closed.complete(Unit)
            closed.thenRun { readers.forEach { it.close() }; thread.quitSafely() }.get(5, TimeUnit.SECONDS)
        }
    }

    private data class CaptureMetadata(val logical: TotalCaptureResult, val physical: TotalCaptureResult)
    private data class RetainedPair(val id: String, val dngBytes: Long)

    @RequiresApi(33)
    private fun retain(cache: File, info: CameraCharacteristics, result: CaptureMetadata,
                       request: CaptureRequest, raw: Image, jpeg: Jpeg, focal: Float, iso: Int,
                       exposure: Long, preview: Boolean, acquisitionMs: Long): RetainedPair {
        val id = UUID.randomUUID().toString()
        val directory = File(cache, "quality-pair-$id")
        check(directory.mkdir())
        val dngFile = File(directory, "source.dng")
        dngFile.outputStream().use { output ->
            DngCreator(info, result.physical).use { it.setOrientation(jpeg.orientation).writeImage(output, raw) }
        }
        check(dngFile.length() >= 2L * raw.width * raw.height)
        val jpegFile = File(directory, "source.jpg")
        jpegFile.writeBytes(checkNotNull(jpeg.bytes))
        // Keep the existing calibration replay input alongside the versioned bundle.
        File(directory, "exposure.txt").writeText("$iso\n$exposure\n")

        val physical = result.physical
        val crop = physical[CaptureResult.SCALER_CROP_REGION]?.let {
            JSONArray(listOf(it.left, it.top, it.right, it.bottom))
        }
        val noise = physical[CaptureResult.SENSOR_NOISE_PROFILE]?.let { pairs ->
            JSONArray(pairs.map { JSONArray(listOf(it.first, it.second)) })
        }
        val manifest = JSONObject()
            .put("schemaVersion", 1).put("pairId", id)
            .put("deviceModel", Build.MODEL).put("firmware", Build.VERSION.INCREMENTAL)
            .put("logicalCameraId", "0").put("physicalCameraId", "6")
            .put("logicalActivePhysicalId", result.logical[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID] ?: JSONObject.NULL)
            .put("focalLengthMm", focal.toDouble()).put("iso", iso).put("exposureNs", exposure)
            .put("sensorTimestampNs", raw.timestamp).put("frameNumber", result.logical.frameNumber)
            .put("timestampSource", info[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE] ?: JSONObject.NULL)
            .put("nominalOpticalZoom", 3.0)
            .put("digitalZoomRequested", request[CaptureRequest.CONTROL_ZOOM_RATIO]?.toDouble() ?: 1.0)
            .put("logicalZoomRatio", result.logical[CaptureResult.CONTROL_ZOOM_RATIO]?.toDouble() ?: JSONObject.NULL)
            .put("physicalZoomRatio", physical[CaptureResult.CONTROL_ZOOM_RATIO]?.toDouble() ?: JSONObject.NULL)
            .put("sensorCropRegion", crop ?: JSONObject.NULL).put("previewEnabled", preview)
            .put("previewDimensions", if (preview) JSONArray(listOf(1440, 1080)) else JSONObject.NULL)
            .put("rawAndJpegMs", acquisitionMs).put("sensorNoiseProfile", noise ?: JSONObject.NULL)
            .put("dng", artifact(dngFile, raw.width, raw.height, raw.timestamp, jpeg.orientation))
            .put("jpeg", artifact(jpegFile, jpeg.width, jpeg.height, jpeg.timestamp, jpeg.orientation)
                .put("focalLengthMm", jpeg.focalMm).put("exifIso", jpeg.iso ?: JSONObject.NULL)
                .put("exifExposureSeconds", jpeg.exposureSeconds ?: JSONObject.NULL))
        val temporary = File(directory, "manifest.tmp")
        temporary.writeText(manifest.toString(2) + "\n")
        check(temporary.renameTo(File(directory, "manifest.json")))
        return RetainedPair(id, dngFile.length())
    }

    private fun artifact(file: File, width: Int, height: Int, timestamp: Long, orientation: Int): JSONObject {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return JSONObject().put("filename", file.name).put("width", width).put("height", height)
            .put("timestampNs", timestamp).put("orientation", orientation).put("bytes", file.length())
            .put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
    }

    private data class Jpeg(val timestamp: Long, val width: Int, val height: Int, val focalMm: Double,
                            val orientation: Int, val iso: Int?, val exposureSeconds: Double?, val bytes: ByteArray?)

    private class ByteCount : OutputStream() {
        var bytes = 0L
            private set
        override fun write(b: Int) { bytes++ }
        override fun write(b: ByteArray, off: Int, len: Int) { bytes += len }
    }
}
