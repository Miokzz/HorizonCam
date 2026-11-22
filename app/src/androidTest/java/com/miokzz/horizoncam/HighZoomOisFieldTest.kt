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
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ExifInterface
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Metadata baseline for the same native tele source used by 20x and 30x photos. */
class HighZoomOisFieldTest {
    @Test fun probeOpticalStabilizationForTelePhoto() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("highZoomOis") == "yes")
        assumeTrue(Build.MODEL == "SM-S9210" && Build.VERSION.SDK_INT >= 33)
        val manager = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(CameraManager::class.java)
        val logical = manager.getCameraCharacteristics(LOGICAL_ID)
        val characteristics = listOf(LOGICAL_ID, "2", "5", TELE_ID).mapNotNull { id ->
            runCatching { manager.getCameraCharacteristics(id) }.fold(onSuccess = { info ->
                val clock = info[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE]
                report("id=$id oisAvailable=${availableOis(info).map(::oisName)} " +
                    "videoEisAvailable=${info[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES]?.toList()?.map(::eisName)} " +
                    "timestampSource=${when (clock) {
                        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
                        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN"
                        else -> clock
                    }} " +
                    "oisDataAvailable=${info[CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES]?.toList()} " +
                    "intrinsicsCount=${info[CameraCharacteristics.LENS_INTRINSIC_CALIBRATION]?.size ?: 0}")
                id to info
            }, onFailure = { error ->
                report("id=$id characteristics=unavailable error=${describe(error)}")
                null
            })
        }.toMap()
        val tele = characteristics[TELE_ID] ?: error("Sem características da tele física 6.")
        val defaults = Route.entries.associateWith { route -> probeRoute(manager, logical, tele, route) }
        report("hostZoom=20x,30x halZoom=observed_tele_base defaultOisByRoute=$defaults " +
            "forcingOn=${if (defaults.values.all { it == true }) "redundant_for_mode_selection" else "not_established"}")
    }

    private fun probeRoute(
        manager: CameraManager, logical: CameraCharacteristics, tele: CameraCharacteristics, route: Route
    ): Boolean? {
        val thread = HandlerThread("hc-photo-ois-probe").apply { start() }
        val handler = Handler(thread.looper)
        val lease = DeviceLease(handler)
        var preview: ImageReader? = null
        var jpeg: ImageReader? = null
        try {
            val source = if (route.pinned) tele else logical
            val streams = source[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            if (streams == null || PREVIEW_SIZE !in streams.getOutputSizes(ImageFormat.PRIVATE).orEmpty() ||
                route.jpegSize !in streams.getOutputSizes(ImageFormat.JPEG).orEmpty()) {
                report("route=$route status=not_attempted reason=output_size_not_advertised")
                return null
            }
            val device = lease.open(manager)
            preview = ImageReader.newInstance(PREVIEW_SIZE.width, PREVIEW_SIZE.height, ImageFormat.PRIVATE, 4,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
            jpeg = ImageReader.newInstance(route.jpegSize.width, route.jpegSize.height, ImageFormat.JPEG, 2)
            val probe = SessionProbe(device, handler, preview, jpeg, route)
            val config = probe.configuration()
            val query = runCatching { device.isSessionConfigurationSupported(config) }
            report("route=$route preview=$PREVIEW_SIZE jpeg=${route.jpegSize} " +
                "supportQuery=${query.getOrNull() ?: describe(query.exceptionOrNull())}")
            if (query.getOrNull() == false) return null
            device.createCaptureSession(config)
            probe.awaitConfigured()

            val baseline = probe.measure("default", OisOverride())
            val explicit = OisOverride(
                logical = ON in availableOis(logical) && OIS in logical.availableCaptureRequestKeys,
                physical = route.pinned && ON in availableOis(tele) &&
                    OIS in logical.availablePhysicalCameraRequestKeys.orEmpty()
            )
            if (explicit.logical || explicit.physical) probe.measure("explicit_on", explicit)
            else report("route=$route variant=explicit_on status=not_attempted reason=mode_or_request_key_not_advertised")
            return baseline
        } catch (error: Exception) {
            report("route=$route status=failed error=${describe(error)}")
            return null
        } finally {
            val cleanup = lease.closeFirst {
                preview?.close()
                jpeg?.close()
                thread.quitSafely()
            }
            try {
                cleanup.get(4, TimeUnit.SECONDS)
                report("route=$route closeAcknowledged=true")
            } catch (error: Exception) {
                report("route=$route closeAcknowledged=false readersRetainedForOnClosed=true")
                throw error
            }
        }
    }

    private data class OisOverride(val logical: Boolean = false, val physical: Boolean = false)

    private class SessionProbe(
        private val device: CameraDevice,
        private val handler: Handler,
        private val preview: ImageReader,
        private val jpeg: ImageReader,
        private val route: Route
    ) {
        private val configured = CompletableFuture<CameraCaptureSession>()
        private lateinit var session: CameraCaptureSession
        @Volatile private var window: PreviewWindow? = null
        @Volatile private var shot: JpegShot? = null

        fun configuration(): SessionConfiguration {
            preview.setOnImageAvailableListener({ reader ->
                reader.acquireNextImage()?.use { window?.image(it.timestamp) }
            }, handler)
            jpeg.setOnImageAvailableListener({ reader ->
                reader.acquireNextImage()?.use { image ->
                    val current = shot ?: return@use
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    current.jpeg.complete(Jpeg(image.timestamp, Size(image.width, image.height), bytes))
                }
            }, handler)
            val outputs = listOf(preview.surface, jpeg.surface).map {
                OutputConfiguration(it).apply {
                    if (route.pinned) setPhysicalCameraId(TELE_ID)
                    if (Build.VERSION.SDK_INT >= 33) timestampBase = OutputConfiguration.TIMESTAMP_BASE_SENSOR
                }
            }
            return SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs,
                { handler.post(it) }, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) { configured.complete(session) }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        configured.completeExceptionally(IllegalStateException("HAL recusou a sessão de foto."))
                    }
                })
        }

        fun awaitConfigured() { session = configured.get(4, TimeUnit.SECONDS) }

        fun measure(name: String, forceOis: OisOverride): Boolean? {
            val samples = PreviewWindow(route)
            window = samples
            val previewRequest = request(CameraDevice.TEMPLATE_PREVIEW, forceOis)
            reportRequest(name, "preview", previewRequest, forceOis)
            session.setRepeatingRequest(previewRequest, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    samples.result(read(result))
                }
            }, handler)
            val frames = try { samples.ready.get(2_500, TimeUnit.MILLISECONDS) } catch (error: Exception) {
                report("route=$route variant=$name stream=preview status=unverified lensFrames=${samples.count} error=${describe(error)}")
                return null
            } finally {
                window = null
            }
            report("route=$route variant=$name stream=preview status=verified matched=${frames.size} " +
                "activeIds=${frames.map { it.activeId }.distinct()} logicalZoom=${frames.map { it.zoom }.distinct()} " +
                "logical=${summarize(frames.map { it.logical })} physical6=${summarize(frames.map { it.physical })}")

            val current = JpegShot()
            shot = current
            try {
                val jpegRequest = request(CameraDevice.TEMPLATE_STILL_CAPTURE, forceOis)
                reportRequest(name, "jpeg", jpegRequest, forceOis)
                session.capture(jpegRequest, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        current.frame.complete(read(result))
                    }

                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        val error = IllegalStateException("CaptureFailure=${failure.reason}")
                        current.frame.completeExceptionally(error)
                        current.jpeg.completeExceptionally(error)
                    }
                }, handler)
                CompletableFuture.allOf(current.frame, current.jpeg).get(3, TimeUnit.SECONDS)
                val frame = current.frame.get()
                val image = current.jpeg.get()
                val focalExif = ExifInterface(image.bytes.inputStream())
                    .getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1.0)
                val verified = frame.isTele(route) && frame.matches(image.timestamp, route) &&
                    abs(focalExif - EXPECTED_MM) <= FOCAL_TOLERANCE && image.size == route.jpegSize
                report("route=$route variant=$name stream=jpeg status=${if (verified) "verified" else "unverified"} " +
                    "activeId=${frame.activeId} logicalZoom=${frame.zoom} focalExifMm=$focalExif output=${image.size} " +
                    "imageTimestampNs=${image.timestamp} logical=${frame.logical} physical6=${frame.physical}")
                if (!verified) return null
                val applied = frames.map { it.reading(route)?.ois } + frame.reading(route)?.ois
                return if (applied.any { it == null }) null else applied.all { it == ON }
            } catch (error: Exception) {
                report("route=$route variant=$name stream=jpeg status=unverified error=${describe(error)}")
                return null
            } finally {
                shot = null
            }
        }

        private fun request(template: Int, forceOis: OisOverride): CaptureRequest {
            val builder = if (forceOis.physical) device.createCaptureRequest(template, setOf(TELE_ID))
                else device.createCaptureRequest(template)
            return builder.apply {
                addTarget(preview.surface)
                if (!route.pinned && Build.VERSION.SDK_INT >= 30) set(CaptureRequest.CONTROL_ZOOM_RATIO, 3f)
                if (template == CameraDevice.TEMPLATE_STILL_CAPTURE) {
                    addTarget(jpeg.surface)
                    set(CaptureRequest.JPEG_ORIENTATION, 0)
                    set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                }
                if (forceOis.logical) set(OIS, ON)
                if (forceOis.physical) setPhysicalCameraKey(OIS, ON, TELE_ID)
            }.build()
        }

        private fun reportRequest(name: String, stream: String, request: CaptureRequest, force: OisOverride) {
            report("route=$route variant=$name stream=$stream requestedOisLogical=${oisName(request[OIS])} " +
                "requestedOisPhysical6=${if (force.physical) "ON" else "default"} " +
                "requestedVideoEis=${eisName(request[CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE])}")
        }
    }

    private class PreviewWindow(private val route: Route) {
        val ready = CompletableFuture<List<Frame>>()
        private val records = mutableListOf<Frame>()
        private val images = linkedSetOf<Long>()
        private val matched = mutableListOf<Frame>()
        val count: Int @Synchronized get() = matched.size

        @Synchronized fun image(timestamp: Long) {
            if (ready.isDone) return
            images += timestamp
            if (images.size > 64) images.remove(images.first())
            match()
        }

        @Synchronized fun result(frame: Frame) {
            if (ready.isDone || !frame.isTele(route)) return
            records += frame
            if (records.size > 64) records.removeAt(0)
            match()
        }

        private fun match() {
            val pending = records.iterator()
            while (pending.hasNext()) {
                val frame = pending.next()
                val timestamp = images.firstOrNull { frame.matches(it, route) } ?: continue
                pending.remove()
                images.remove(timestamp)
                matched += frame
                if (matched.size == WARM_UP_FRAMES + SAMPLE_FRAMES) {
                    ready.complete(matched.drop(WARM_UP_FRAMES))
                    return
                }
            }
        }
    }

    private class JpegShot {
        val frame = CompletableFuture<Frame>()
        val jpeg = CompletableFuture<Jpeg>()
    }
    private data class Jpeg(val timestamp: Long, val size: Size, val bytes: ByteArray)

    private data class Reading(
        val focalMm: Float?, val ois: Int?, val eis: Int?, val exposureTimeNs: Long?, val iso: Int?,
        val timestampNs: Long?, val resultArrivalElapsedNs: Long
    ) {
        override fun toString() = "{focalMm=$focalMm ois=${oisName(ois)} eis=${eisName(eis)} " +
            "exposureTimeNs=$exposureTimeNs iso=$iso timestampNs=$timestampNs resultArrivalElapsedNs=$resultArrivalElapsedNs}"
    }

    private data class Frame(val activeId: String?, val zoom: Float?, val logical: Reading, val physical: Reading?) {
        fun reading(route: Route): Reading? = if (route.pinned) physical else logical
        fun isTele(route: Route): Boolean {
            val focal = reading(route)?.focalMm ?: return false
            if (abs(focal - EXPECTED_MM) > FOCAL_TOLERANCE) return false
            return route.pinned || (activeId == TELE_ID && zoom != null && abs(zoom - 3f) <= 0.02f)
        }
        fun matches(timestamp: Long, route: Route): Boolean =
            timestamp == logical.timestampNs || (route.pinned && timestamp == physical?.timestampNs)
    }

    private class DeviceLease(private val handler: Handler) {
        private val opened = CompletableFuture<CameraDevice>()
        private val closed = CompletableFuture<Unit>()
        private val current = AtomicReference<CameraDevice?>()
        private val closing = AtomicBoolean()
        private var submitted = false

        @SuppressLint("MissingPermission")
        fun open(manager: CameraManager): CameraDevice {
            manager.openCamera(LOGICAL_ID, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    current.set(camera)
                    if (closing.get()) camera.close() else opened.complete(camera)
                }

                override fun onDisconnected(camera: CameraDevice) = onError(camera, ERROR_CAMERA_DEVICE)
                override fun onError(camera: CameraDevice, error: Int) {
                    opened.completeExceptionally(IllegalStateException("CameraDevice error=$error"))
                    camera.close()
                }
                override fun onClosed(camera: CameraDevice) { closed.complete(Unit) }
            }, handler)
            submitted = true
            return opened.get(4, TimeUnit.SECONDS)
        }

        fun closeFirst(releaseOutputs: () -> Unit): CompletableFuture<Void> {
            closing.set(true)
            current.get()?.close()
            if (!submitted) closed.complete(Unit)
            return closed.thenRun(releaseOutputs)
        }
    }

    private enum class Route(val pinned: Boolean, val jpegSize: Size) {
        PHYSICAL_0_6(true, Size(3648, 2736)),
        LOGICAL_0_TELE(false, Size(4080, 3060))
    }

    private companion object {
        const val TAG = "HC_HIGH_ZOOM_OIS"
        const val LOGICAL_ID = "0"
        const val TELE_ID = "6"
        const val EXPECTED_MM = 7.0
        const val FOCAL_TOLERANCE = 0.05
        const val WARM_UP_FRAMES = 4
        const val SAMPLE_FRAMES = 12
        const val ON = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
        val OIS = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE
        val PREVIEW_SIZE = Size(1440, 1080)

        fun availableOis(info: CameraCharacteristics) =
            info[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]?.toList().orEmpty()

        @Suppress("DEPRECATION")
        fun read(result: TotalCaptureResult): Frame {
            val elapsed = SystemClock.elapsedRealtimeNanos()
            val physical = if (Build.VERSION.SDK_INT >= 31) {
                result.physicalCameraTotalResults[TELE_ID] ?: result.physicalCameraResults[TELE_ID]
            } else result.physicalCameraResults[TELE_ID]
            val zoom = if (Build.VERSION.SDK_INT >= 30) result[CaptureResult.CONTROL_ZOOM_RATIO] else null
            return Frame(result[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID], zoom, reading(result, elapsed),
                physical?.let { reading(it, elapsed) })
        }

        fun reading(result: CaptureResult, elapsed: Long) =
            Reading(result[CaptureResult.LENS_FOCAL_LENGTH], result[CaptureResult.LENS_OPTICAL_STABILIZATION_MODE],
                result[CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE], result[CaptureResult.SENSOR_EXPOSURE_TIME],
                result[CaptureResult.SENSOR_SENSITIVITY], result[CaptureResult.SENSOR_TIMESTAMP], elapsed)

        fun summarize(readings: List<Reading?>): String {
            val ages = readings.mapNotNull { item -> item?.timestampNs?.let { item.resultArrivalElapsedNs - it } }
            return "{reported=${readings.count { it != null }} focalMm=${readings.map { it?.focalMm }.distinct()} " +
                "ois=${readings.groupingBy { oisName(it?.ois) }.eachCount()} " +
                "eis=${readings.groupingBy { eisName(it?.eis) }.eachCount()} " +
                "exposureTimeNs=${range(readings.mapNotNull { it?.exposureTimeNs })} " +
                "iso=${range(readings.mapNotNull { it?.iso })} arrivalMinusSensorNs=${range(ages)}}"
        }

        fun <T : Comparable<T>> range(values: List<T>) = values.minOrNull()?.let { "$it..${values.maxOrNull()}" }

        fun oisName(value: Int?): String = when (value) {
            CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_OFF -> "OFF"
            CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON -> "ON"
            null -> "unreported"
            else -> value.toString()
        }

        fun eisName(value: Int?): String = when (value) {
            CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_OFF -> "OFF"
            CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "ON"
            CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION -> "PREVIEW_STABILIZATION"
            null -> "unreported"
            else -> value.toString()
        }

        fun describe(error: Throwable?): String {
            val cause = error?.cause ?: error ?: return "unreported"
            return "${cause.javaClass.simpleName}:${cause.message.orEmpty().replace('\n', ' ')}"
        }

        fun report(message: String) {
            Log.i(TAG, message)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\n$TAG $message\n")
            })
        }
    }
}
