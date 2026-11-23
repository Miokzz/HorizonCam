package com.miokzz.horizoncam

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCaptureSession
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
import androidx.test.platform.app.InstrumentationRegistry
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Tests paired tele RAW and JPEG without publishing or storing any photograph. */
class TeleRawPairFieldTest {
    @Test fun capturePairedTeleOutputs() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rawPair") == "yes")
        assumeTrue(Build.MODEL == "SM-S9210" && Build.VERSION.SDK_INT >= 33)
        val runner = InstrumentationRegistry.getInstrumentation()
        val manager = runner.targetContext.getSystemService(CameraManager::class.java)
        fun report(line: String) = runner.sendStatus(0, Bundle().apply {
            putString("stream", "\nHC_RAW_PAIR $line\n")
        })
        if (!attempt(manager, true, ::report)) attempt(manager, false, ::report)
    }

    @SuppressLint("MissingPermission")
    private fun attempt(manager: CameraManager, withPreview: Boolean, report: (String) -> Unit): Boolean {
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
                    val focal = ExifInterface(bytes.inputStream()).getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1.0)
                    Jpeg(image.timestamp, image.width, image.height, focal)
                }.fold(jpeg::complete, jpeg::completeExceptionally)
            } }, handler)

            val configured = CompletableFuture<CameraCaptureSession>()
            val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, readers.map { reader ->
                OutputConfiguration(reader.surface).apply {
                    setPhysicalCameraId("6")
                    if (Build.VERSION.SDK_INT >= 33) timestampBase = OutputConfiguration.TIMESTAMP_BASE_SENSOR
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
            val meta = CompletableFuture<TotalCaptureResult>()
            val started = SystemClock.elapsedRealtime()
            session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (Build.VERSION.SDK_INT >= 31) result.physicalCameraTotalResults["6"]?.let(meta::complete)
                    if (!meta.isDone) meta.completeExceptionally(IllegalStateException("missing_tele_result"))
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    meta.completeExceptionally(IllegalStateException("capture_failed_${failure.reason}"))
                }
            }, handler)
            val physical = meta.get(3, TimeUnit.SECONDS)
            val still = jpeg.get(4, TimeUnit.SECONDS)
            val image = raw.get(3, TimeUnit.SECONDS)
            val acquisitionMs = SystemClock.elapsedRealtime() - started
            val exposureStart = physical[CaptureResult.SENSOR_TIMESTAMP]
            val focal = physical[CaptureResult.LENS_FOCAL_LENGTH]
            val verified = exposureStart == image.timestamp && exposureStart == still.timestamp &&
                focal != null && abs(focal - 7f) <= 0.05f && abs(still.focalMm - 7.0) <= 0.05 &&
                image.width == still.width && image.height == still.height && image.format == ImageFormat.RAW_SENSOR
            val output = ByteCount()
            DngCreator(manager.getCameraCharacteristics("6"), physical).use { it.writeImage(output, image) }
            val dngVerified = output.bytes >= 2L * image.width * image.height
            report("preview=$withPreview status=${if (verified && dngVerified) "verified" else "unverified"} " +
                "raw=${image.width}x${image.height} jpeg=${still.width}x${still.height} focalRaw=$focal " +
                "focalJpeg=${still.focalMm} equalTimestamps=${exposureStart == image.timestamp && image.timestamp == still.timestamp} " +
                "exposureNs=${physical[CaptureResult.SENSOR_EXPOSURE_TIME]} iso=${physical[CaptureResult.SENSOR_SENSITIVITY]} " +
                "rawAndJpegMs=$acquisitionMs dngBytes=${output.bytes}")
            return verified && dngVerified
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

    private data class Jpeg(val timestamp: Long, val width: Int, val height: Int, val focalMm: Double)

    private class ByteCount : OutputStream() {
        var bytes = 0L
            private set
        override fun write(b: Int) { bytes++ }
        override fun write(b: ByteArray, off: Int, len: Int) { bytes += len }
    }
}
