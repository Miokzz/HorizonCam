package com.miokzz.horizoncam

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.graphics.SurfaceTexture
import android.view.Surface
import android.media.ExifInterface
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Explicit field run. Open under the target package and capture a private in-memory JPEG. */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class AuxiliaryCameraAccessTest {
    private data class JpegResult(val cameraId: String, val size: android.util.Size, val focal: Float?,
        val physicalFocal: Float?, val exifFocal: Double, val active: String?) {
        override fun toString() = "opened=$cameraId jpeg=$size focalResult=$focal " +
            "physicalFocal=$physicalFocal focalExif=$exifFocal active=$active"
    }

    @Test fun captureWithPublicCamera2() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("auxProbe") == "yes")
        val runner = InstrumentationRegistry.getInstrumentation()
        val context = runner.targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        val ids = manager.cameraIdList.toSet()
        val provider = ProcessCameraProvider.getInstance(context).get(15, TimeUnit.SECONDS)
        val cameraX = provider.availableCameraInfos.map { Camera2CameraInfo.from(it).cameraId }
        fun report(line: String) {
            runner.sendStatus(0, Bundle().apply { putString("stream", "\n$line\n") })
        }
        report("package=${context.packageName} camera2=$ids cameraX=$cameraX")
        val thread = HandlerThread("aux-probe").apply { start() }
        try {
            val handler = Handler(thread.looper)
            for (id in listOf("0", "2", "52", "56", "58")) {
                val characteristics = runCatching { manager.getCameraCharacteristics(id) }
                // Querying metadata, enumeration and opening are independent observations.
                val result = runCatching { probe(manager, id, characteristics.getOrNull(), handler) }
                report("id=$id listed=${id in ids} meta=${characteristics.isSuccess} " +
                    (result.getOrNull()?.toString() ?: "failed=${result.exceptionOrNull()}"))
                if (id == "0") assertTrue("Control camera 0: $result", result.isSuccess)
            }
            val expectS24Tele = InstrumentationRegistry.getArguments().getString("requireS24Tele") == "yes"
            val hasTeleMember = "6" in manager.getCameraCharacteristics("0").physicalCameraIds
            if (expectS24Tele) assertTrue("The S24 firmware did not advertise the physical tele member", hasTeleMember)
            if (hasTeleMember) {
                val direct = runCatching { probe(manager, "0", manager.getCameraCharacteristics("6"), handler, "6") }
                report("physical=0/6 " + (direct.getOrNull()?.toString() ?: "failed=${direct.exceptionOrNull()}"))
                if (expectS24Tele) {
                    val jpeg = direct.getOrThrow()
                    assertEquals("0", jpeg.cameraId)
                    assertEquals("6", jpeg.active)
                    assertEquals(7.0, jpeg.physicalFocal?.toDouble() ?: 0.0, 0.05)
                    assertEquals(7.0, jpeg.exifFocal, 0.05)
                }
            }
        } finally { thread.quitSafely(); thread.join(2000) }
    }

    @Test fun reopenMainAfterClosingPhysicalTeleDevice() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("teleDeviceClose") == "yes")
        assumeTrue(android.os.Build.MODEL == "SM-S9210" && android.os.Build.VERSION.SDK_INT >= 31)
        val runner = InstrumentationRegistry.getInstrumentation()
        val manager = runner.targetContext.getSystemService(CameraManager::class.java)
        val thread = HandlerThread("tele-device-close").apply { start() }
        try {
            val handler = Handler(thread.looper)
            for (cycle in 1..4) {
                val tele = previewAndCloseDevice(manager, handler, "6")
                assertEquals(7.0, tele.first.toDouble(), 0.05)
                val main = previewAndCloseDevice(manager, handler, null)
                assertEquals(5.4, main.first.toDouble(), 0.05)
                runner.sendStatus(0, Bundle().apply {
                    putString("stream", "\ncycle=$cycle teleMm=${tele.first} mainMm=${main.first} " +
                        "teleCloseMs=${tele.second} mainCloseMs=${main.second}\n")
                })
            }
        } finally {
            thread.quitSafely()
            thread.join(2000)
        }
    }

    @SuppressLint("MissingPermission")
    private fun previewAndCloseDevice(manager: CameraManager, handler: Handler, physicalId: String?): Pair<Float, Long> {
        val opened = CompletableFuture<CameraDevice>()
        val closed = CompletableFuture<Unit>()
        val focal = CompletableFuture<Float>()
        manager.openCamera("0", object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) { if (!opened.complete(camera)) camera.close() }
            override fun onClosed(camera: CameraDevice) { closed.complete(Unit) }
            override fun onDisconnected(camera: CameraDevice) = onError(camera, ERROR_CAMERA_DEVICE)
            override fun onError(camera: CameraDevice, error: Int) {
                val failure = IllegalStateException("Erro de Camera2: $error")
                opened.completeExceptionally(failure)
                focal.completeExceptionally(failure)
                camera.close()
            }
        }, handler)
        val device = try { opened.get(6, TimeUnit.SECONDS) }
            catch (error: Exception) { opened.cancel(false); throw error }
        val jpegSize = if (physicalId != null) android.util.Size(3648, 2736) else android.util.Size(4080, 3060)
        var previewReader: ImageReader? = null
        var jpegReader: ImageReader? = null
        try {
            val preview = ImageReader.newInstance(1440, 1080, ImageFormat.PRIVATE, 3,
                android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE).also { previewReader = it }
            val jpeg = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
                .also { jpegReader = it }
            preview.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
            val configured = CompletableFuture<CameraCaptureSession>()
            val outputs = listOf(preview.surface, jpeg.surface).map { surface ->
                OutputConfiguration(surface).apply { physicalId?.let(::setPhysicalCameraId) }
            }
            device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs,
                { handler.post(it) }, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) { configured.complete(session) }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        configured.completeExceptionally(IllegalStateException("Sessão recusada"))
                    }
                }))
            val session = configured.get(6, TimeUnit.SECONDS)
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                .apply { addTarget(preview.surface) }.build()
            var frames = 0
            session.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    val measurement = if (physicalId == null) result else result.physicalCameraTotalResults[physicalId]
                    measurement?.get(CaptureResult.LENS_FOCAL_LENGTH)?.let {
                        if (++frames >= 6) focal.complete(it)
                    }
                }
            }, handler)
            val millimeters = focal.get(6, TimeUnit.SECONDS)
            val started = android.os.SystemClock.elapsedRealtime()
            // Leave the repeating session attached. Closing CameraCaptureSession first
            // can make the framework configure an empty session on this device.
            device.close()
            closed.get(6, TimeUnit.SECONDS)
            return millimeters to (android.os.SystemClock.elapsedRealtime() - started)
        } finally {
            device.close()
            runCatching { closed.get(6, TimeUnit.SECONDS) }
            previewReader?.close()
            jpegReader?.close()
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun probe(manager: CameraManager, id: String, chars: CameraCharacteristics?, handler: Handler,
                      physicalId: String? = null): JpegResult {
        val opened = CompletableFuture<CameraDevice>()
        val closed = CompletableFuture<Unit>()
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) { if (!opened.complete(camera)) camera.close() }
            override fun onDisconnected(camera: CameraDevice) { opened.completeExceptionally(Exception("disconnected")); camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { opened.completeExceptionally(Exception("open error=$error")); camera.close() }
            override fun onClosed(camera: CameraDevice) { closed.complete(Unit) }
        }, handler)
        val device = try { opened.get(5, TimeUnit.SECONDS) } catch (e: Exception) { opened.cancel(false); throw e }
        try {
            val streams = requireNotNull(chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
            val sizes = streams.getOutputSizes(ImageFormat.JPEG)
            val size = (sizes.filter { it.width >= 640 }.ifEmpty { sizes.toList() }).minBy { it.width.toLong() * it.height }
            ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).use { reader ->
                val imageFocal = CompletableFuture<Double>()
                reader.setOnImageAvailableListener({ queue ->
                    runCatching {
                        queue.acquireNextImage().use { image ->
                            val buffer = requireNotNull(image).planes[0].buffer
                            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                            ExifInterface(bytes.inputStream()).getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1.0)
                        }
                    }.fold(imageFocal::complete, imageFocal::completeExceptionally)
                }, handler)
                val configured = CompletableFuture<CameraCaptureSession>()
                val sessionState = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) { if (!configured.complete(session)) session.close() }
                    override fun onConfigureFailed(session: CameraCaptureSession) { configured.completeExceptionally(Exception("JPEG session refused")) }
                }
                val texture = physicalId?.let { SurfaceTexture(false) }
                val preview = texture?.let {
                    val previewSize = streams.getOutputSizes(SurfaceTexture::class.java)
                        .filter { size -> size.width >= 640 }.minBy { size -> size.width.toLong() * size.height }
                    it.setDefaultBufferSize(previewSize.width, previewSize.height)
                    Surface(it)
                }
                try {
                    if (physicalId == null) device.createCaptureSession(listOf(reader.surface), sessionState, handler)
                    else {
                        val outputs = listOf(requireNotNull(preview), reader.surface).map { surface ->
                            OutputConfiguration(surface).apply { setPhysicalCameraId(physicalId) }
                        }
                        val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs,
                            { handler.post(it) }, sessionState)
                        val supported = try { device.isSessionConfigurationSupported(config) }
                            catch (_: UnsupportedOperationException) { null }
                        check(supported != false) { "HAL rejected preview+JPEG physical=$physicalId" }
                        device.createCaptureSession(config)
                    }
                    configured.get(5, TimeUnit.SECONDS).use { session ->
                        val frame = CompletableFuture<TotalCaptureResult>()
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(reader.surface)
                            preview?.let(::addTarget)
                        }.build()
                        session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) { frame.complete(result) }
                            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                                frame.completeExceptionally(Exception("capture failure ${failure.reason}"))
                            }
                        }, handler)
                        val result = frame.get(6, TimeUnit.SECONDS)
                        return JpegResult(device.id, size, result.get(CaptureResult.LENS_FOCAL_LENGTH),
                            physicalId?.let { result.physicalCameraResults[it]?.get(CaptureResult.LENS_FOCAL_LENGTH) },
                            imageFocal.get(6, TimeUnit.SECONDS),
                            result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID))
                    }
                } finally { preview?.release(); texture?.release() }
            }
        } finally { device.close(); closed.get(5, TimeUnit.SECONDS) }
    }
}
