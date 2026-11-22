package com.miokzz.horizoncam

import android.annotation.SuppressLint
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
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Capability and delivery audit. Captured RAW pixels never leave the ImageReader. */
class RawPhotoFieldTest {
    private data class Route(val device: String, val physical: String?, val zoom: Float, val expectedMm: Float) {
        val source = physical ?: device
        val name = physical?.let { "$device/$it" } ?: "$device@${zoom}x"
    }

    private data class RawBuffer(val size: Size, val timestamp: Long, val format: Int, val rowStride: Int, val pixelStride: Int)

    @Test fun compareRawDeliveryAcrossPhotoRoutes() {
        val runner = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("rawPhoto") == "yes")
        assumeTrue(Build.MODEL == "SM-S9210" && Build.VERSION.SDK_INT >= 33)
        val manager = runner.targetContext.getSystemService(CameraManager::class.java)
        val routes = listOf(Route("0", null, 1f, 5.4f), Route("2", null, 1f, 2.2f),
            Route("0", "5", 1f, 5.4f), Route("0", "6", 1f, 7f), Route("0", null, 3f, 7f))
        val infoById = routes.map { it.source }.distinct().associateWith(manager::getCameraCharacteristics)
        fun report(message: String) = runner.sendStatus(0, Bundle().apply {
            putString("stream", "\nHC_RAW_PHOTO $message\n")
        })
        val rawById = infoById.mapValues { (id, info) ->
            val raw = info[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
            val sizes = info[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
                ?.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty().toList()
            report("id=$id direct=${id in manager.cameraIdList} rawCapability=$raw rawSizes=$sizes " +
                "cfa=${info[CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT]} " +
                "whiteLevel=${info[CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL]} " +
                "noiseProfileKey=${CaptureResult.SENSOR_NOISE_PROFILE in info.availableCaptureResultKeys.orEmpty()}")
            sizes
        }
        for (route in routes) {
            if (route.device !in manager.cameraIdList) {
                report("route=${route.name} status=not_attempted reason=device_not_visible")
                continue
            }
            val size = rawById.getValue(route.source).maxByOrNull { it.width.toLong() * it.height }
            if (size == null) report("route=${route.name} status=not_attempted reason=no_raw_size")
            else capture(manager, route, size, ::report)
        }
    }

    @SuppressLint("MissingPermission")
    private fun capture(manager: CameraManager, route: Route, size: Size, report: (String) -> Unit) {
        val thread = HandlerThread("hc-raw-photo-audit").apply { start() }
        val handler = Handler(thread.looper)
        val opened = CompletableFuture<CameraDevice>()
        val closed = CompletableFuture<Unit>()
        val raw = CompletableFuture<RawBuffer>()
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2)
        var submitted = false
        var device: CameraDevice? = null
        try {
            manager.openCamera(route.device, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { if (!opened.complete(camera)) camera.close() }
                override fun onClosed(camera: CameraDevice) { closed.complete(Unit) }
                override fun onDisconnected(camera: CameraDevice) = onError(camera, ERROR_CAMERA_DEVICE)
                override fun onError(camera: CameraDevice, error: Int) {
                    val cause = IllegalStateException("device_error_$error")
                    opened.completeExceptionally(cause)
                    raw.completeExceptionally(cause)
                    camera.close()
                }
            }, handler)
            submitted = true
            val active = opened.get(4, TimeUnit.SECONDS)
            device = active
            reader.setOnImageAvailableListener({ source ->
                source.acquireNextImage()?.use { image ->
                    val plane = image.planes.single()
                    raw.complete(RawBuffer(Size(image.width, image.height), image.timestamp, image.format,
                        plane.rowStride, plane.pixelStride))
                }
            }, handler)
            val configured = CompletableFuture<CameraCaptureSession>()
            val output = OutputConfiguration(reader.surface).apply {
                route.physical?.let(::setPhysicalCameraId)
                if (Build.VERSION.SDK_INT >= 33) timestampBase = OutputConfiguration.TIMESTAMP_BASE_SENSOR
            }
            val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(output),
                { handler.post(it) }, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) { configured.complete(session) }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        configured.completeExceptionally(IllegalStateException("configuration_failed"))
                    }
                })
            val support = runCatching { active.isSessionConfigurationSupported(config) }.getOrNull()
            report("route=${route.name} rawOnlySupportQuery=$support requestedSize=$size")
            if (support == false) return
            active.createCaptureSession(config)
            val session = configured.get(4, TimeUnit.SECONDS)
            val request = active.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                if (Build.VERSION.SDK_INT >= 30 && route.device == "0" && route.physical == null)
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, route.zoom)
            }.build()
            val result = CompletableFuture<TotalCaptureResult>()
            session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, capture: TotalCaptureResult) {
                    result.complete(capture)
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    result.completeExceptionally(IllegalStateException("capture_failed_${failure.reason}"))
                }
            }, handler)
            val metadata = result.get(4, TimeUnit.SECONDS)
            val buffer = raw.get(3, TimeUnit.SECONDS)
            val physical = route.physical?.let { id ->
                if (Build.VERSION.SDK_INT >= 31) metadata.physicalCameraTotalResults[id] else null
            }
            val effective = if (route.physical == null) metadata else physical
            val focal = effective?.get(CaptureResult.LENS_FOCAL_LENGTH)
            val match = buffer.timestamp == effective?.get(CaptureResult.SENSOR_TIMESTAMP) &&
                buffer.size == size && buffer.format == ImageFormat.RAW_SENSOR &&
                focal != null && abs(focal - route.expectedMm) <= 0.05f
            report("route=${route.name} status=${if (match) "verified" else "sensor_unverified"} " +
                "rawSize=${buffer.size} stride=${buffer.rowStride}/${buffer.pixelStride} focalMm=$focal " +
                "active=${metadata[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID]} " +
                "noiseProfilePairs=${effective?.get(CaptureResult.SENSOR_NOISE_PROFILE)?.size ?: 0} " +
                "dynamicBlackLevel=${effective?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL) != null}")
        } catch (error: Exception) {
            report("route=${route.name} status=unverified reason=${error.cause?.message ?: error.javaClass.simpleName}")
        } finally {
            if (device == null) opened.cancel(false)
            device?.close()
            if (submitted) {
                closed.thenRun { reader.close(); thread.quitSafely() }.get(5, TimeUnit.SECONDS)
            } else {
                reader.close()
                thread.quitSafely()
            }
        }
    }
}
