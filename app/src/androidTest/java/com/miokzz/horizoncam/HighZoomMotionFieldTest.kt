package com.miokzz.horizoncam

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import com.miokzz.horizoncam.stabilization.FrameMotion
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Pairs camera streams and measures residual movement in a private, in-memory luma crop. */
class HighZoomMotionFieldTest {
    @Test fun probeTelePhotoMotionStream() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("highZoomMotion") == "yes")
        assumeTrue(Build.MODEL == "SM-S9210" && Build.VERSION.SDK_INT >= 33)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        val sensors = context.getSystemService(SensorManager::class.java)
        for (route in Route.entries) {
            val info = manager.getCameraCharacteristics(if (route.pinned) TELE_ID else LOGICAL_ID)
            val streamMap = info[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            if (streamMap == null || PREVIEW_SIZE !in streamMap.getOutputSizes(ImageFormat.PRIVATE).orEmpty() ||
                route.jpegSize !in streamMap.getOutputSizes(ImageFormat.JPEG).orEmpty()) {
                report("route=$route status=not_attempted reason=photo_output_not_advertised")
                continue
            }
            val advertised = streamMap.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
                .filter { it.width * 3 == it.height * 4 && it.width <= 960 && it.height <= 720 }
            val candidates = listOfNotNull(advertised.maxByOrNull { it.width }, MOTION_FALLBACK.takeIf { it in advertised }).distinct()
            report("route=$route candidates=$candidates jpegStallAdvertisedNs=" +
                streamMap.getOutputStallDuration(ImageFormat.JPEG, route.jpegSize))
            val realtime = info[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE] ==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            val confirmed = candidates.firstOrNull { attempt(manager, sensors, route, it, realtime) }
            report("route=$route confirmedMotionSize=$confirmed")
        }
    }

    private fun attempt(
        manager: CameraManager, sensors: SensorManager, route: Route, motionSize: Size, realtime: Boolean,
    ): Boolean {
        val label = "route=$route motion=$motionSize"
        val thread = HandlerThread("hc-photo-motion-probe").apply { start() }
        val handler = Handler(thread.looper)
        val camera = DeviceLease(handler)
        val readers = mutableListOf<ImageReader>()
        val gyro = GyroWindow()
        try {
            val gyroActive = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
                sensors.registerListener(gyro, it, SensorManager.SENSOR_DELAY_GAME, handler)
            } == true
            val device = camera.open(manager)
            val preview = ImageReader.newInstance(PREVIEW_SIZE.width, PREVIEW_SIZE.height, ImageFormat.PRIVATE, 4,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE).also(readers::add)
            val motion = ImageReader.newInstance(motionSize.width, motionSize.height, ImageFormat.YUV_420_888, 4)
                .also(readers::add)
            val jpeg = ImageReader.newInstance(route.jpegSize.width, route.jpegSize.height, ImageFormat.JPEG, 2)
                .also(readers::add)
            val outputs = mapOf(Stream.PREVIEW to preview, Stream.MOTION to motion, Stream.JPEG to jpeg)
            val probe = Measurements(route, motionSize)
            val imageMotion = ResidualMotion(motionSize)
            outputs.forEach { (stream, reader) ->
                reader.setOnImageAvailableListener({ source ->
                    // Drain in order so this probe does not introduce acquireLatestImage drops.
                    while (true) {
                        val image = source.acquireNextImage() ?: break
                        image.use {
                            val arrival = SystemClock.elapsedRealtimeNanos()
                            val residual = if (stream == Stream.MOTION) imageMotion.measure(it) else null
                            probe.image(stream, Buffer(it.timestamp, arrival, Size(it.width, it.height), it.format, residual))
                        }
                    }
                }, handler)
            }
            val configured = CompletableFuture<CameraCaptureSession>()
            val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs.values.map {
                OutputConfiguration(it.surface).apply {
                    if (route.pinned) setPhysicalCameraId(TELE_ID)
                    if (Build.VERSION.SDK_INT >= 33) timestampBase = OutputConfiguration.TIMESTAMP_BASE_SENSOR
                }
            }, { handler.post(it) }, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) { configured.complete(session) }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    configured.completeExceptionally(IllegalStateException("session_rejected"))
                }
            })
            val query = runCatching { device.isSessionConfigurationSupported(config) }
            report("$label preview=$PREVIEW_SIZE jpeg=${route.jpegSize} supportQuery=" +
                (query.getOrNull() ?: describe(query.exceptionOrNull())))
            device.createCaptureSession(config)
            val session = configured.get(4, TimeUnit.SECONDS)
            report("$label session=configured")

            fun request(still: Boolean) = device.createCaptureRequest(
                if (still) CameraDevice.TEMPLATE_STILL_CAPTURE else CameraDevice.TEMPLATE_PREVIEW
            ).apply {
                setTag(if (still) Stream.JPEG else Stream.MOTION)
                addTarget(preview.surface)
                if (!route.pinned && Build.VERSION.SDK_INT >= 30) set(CaptureRequest.CONTROL_ZOOM_RATIO, 3f)
                if (still) {
                    // Match production: still requests target PRIVATE + JPEG, one intentional YUV omission.
                    addTarget(jpeg.surface)
                    set(CaptureRequest.JPEG_ORIENTATION, 0)
                    set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                } else {
                    addTarget(motion.surface)
                }
            }.build()

            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    probe.result(frame(route, result, request.tag == Stream.JPEG))
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    probe.failed(failure.frameNumber, failure.reason, request.tag == Stream.JPEG)
                }

                override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                    val stream = outputs.entries.firstOrNull { it.value.surface === target }?.key
                    probe.lost(stream, frameNumber)
                }
            }
            session.setRepeatingRequest(request(false), callback, handler)
            val baseline = probe.baseline.get(3, TimeUnit.SECONDS)
            val stillRequest = request(true)
            val submitted = SystemClock.elapsedRealtimeNanos()
            session.capture(stillRequest, callback, handler)
            val shot = probe.shot.get(3, TimeUnit.SECONDS)
            probe.recovered.get(2, TimeUnit.SECONDS)
            val result = probe.snapshot()
            val correctJpeg = shot.frame.confirmedTele && shot.buffer.size == route.jpegSize &&
                shot.buffer.format == ImageFormat.JPEG
            report("$label status=${if (correctJpeg) "verified" else "jpeg_unverified"} " +
                "activeId=${baseline.last().frame.activeId} teleEvidence=${baseline.last().frame.sensorEvidence} " +
                "focalMm=${baseline.last().frame.focalMm} halZoom=${baseline.last().frame.zoom} " +
                "pairedBefore=${baseline.size} pairedAfter=${result.recovered.size} timestampDeltaNs=0 " +
                "previewActual=${baseline.last().preview.size} motionActual=${baseline.last().motion.size}")
            report("$label jpegFilled=${shot.buffer.size} jpegFocalMm=${shot.frame.focalMm} " +
                "jpegActiveId=${shot.frame.activeId} jpegFrame=${shot.frame.number} jpegFrameTimestampNs=${shot.buffer.timestamp} " +
                "jpegSubmitToBufferNs=${shot.buffer.arrival - submitted} yuvTargetedByStill=false")
            report("$label previewBefore=${cadence(baseline.map { it.preview })} yuvBefore=${cadence(baseline.map { it.motion })} " +
                "previewAfter=${cadence(result.recovered.map { it.preview })} yuvAfter=${cadence(result.recovered.map { it.motion })}")
            report("$label ${imageMotion.summarize(baseline)} ${gyro.summarize(baseline, gyroActive, realtime)}")
            report("$label " + result.aroundShot(baseline, shot, submitted))
            return correctJpeg
        } catch (error: Exception) {
            report("$label status=unverified error=${describe(error)}")
            return false
        } finally {
            sensors.unregisterListener(gyro)
            val cleanup = camera.closeFirst {
                readers.forEach { it.close() }
                thread.quitSafely()
            }
            try {
                cleanup.get(4, TimeUnit.SECONDS)
                report("$label closeAcknowledged=true")
            } catch (error: Exception) {
                report("$label closeAcknowledged=false readersRetainedForOnClosed=true")
                throw error
            }
        }
    }

    private enum class Route(val pinned: Boolean, val jpegSize: Size) {
        LOGICAL_0_TELE(false, Size(4080, 3060)),
        PHYSICAL_0_6(true, Size(3648, 2736))
    }
    private enum class Stream { PREVIEW, MOTION, JPEG }
    private data class Buffer(
        val timestamp: Long, val arrival: Long, val size: Size, val format: Int, val residual: MotionSample?,
    )
    private data class Frame(
        val number: Long, val timestamps: List<Long>, val still: Boolean, val activeId: String?,
        val focalMm: Float?, val zoom: Float?, val confirmedTele: Boolean, val sensorEvidence: String,
        val exposureNs: Long?, val opticalOn: Boolean, val electronicOff: Boolean,
    )
    private data class Pairing(val frame: Frame, val preview: Buffer, val motion: Buffer)
    private data class Shot(val frame: Frame, val buffer: Buffer)
    private data class Loss(val stream: Stream?, val frameNumber: Long)
    private data class Failure(val frameNumber: Long, val reason: Int)

    private data class MotionSample(val pixels: Double, val patches: Int)

    private class ResidualMotion(size: Size) {
        private val width = size.width / 10
        private val height = size.height / 10
        private val tracker = if (width >= 48 && height >= 32) FrameMotion(width, height) else null

        fun measure(image: Image): MotionSample? {
            val engine = tracker ?: return null
            val visible = image.cropRect
            if (visible.width() < width || visible.height() < height) return null
            val plane = image.planes[0]
            val bytes = plane.buffer
            val x = visible.centerX() - width / 2
            val y = visible.centerY() - height / 2
            val start = bytes.position() + y * plane.rowStride + x * plane.pixelStride
            val last = start + (height - 1) * plane.rowStride + (width - 1) * plane.pixelStride
            if (start < bytes.position() || last >= bytes.limit()) return null
            for (row in 0 until height) {
                val rowStart = start + row * plane.rowStride
                for (column in 0 until width) engine.input[row * width + column] =
                    bytes.get(rowStart + column * plane.pixelStride)
            }
            engine.advance(image.timestamp)
            val shift = engine.measuredPixels ?: return null
            return MotionSample(hypot(shift.x, shift.y), engine.matchedPatches)
        }

        fun summarize(pairs: List<Pairing>): String {
            val samples = pairs.mapNotNull { it.motion.residual }
            val rms = if (samples.isEmpty()) null else sqrt(samples.map { it.pixels * it.pixels }.average())
            return "postOisSample=${width}x$height matched=${samples.size}/${pairs.size} " +
                "residualRmsPx=$rms residualMaxPx=${samples.maxOfOrNull { it.pixels }} " +
                "patchesMin=${samples.minOfOrNull { it.patches }} activeOis=${pairs.count { it.frame.opticalOn }} " +
                "eisOff=${pairs.count { it.frame.electronicOff }}"
        }
    }

    private class GyroWindow : SensorEventListener {
        private data class Sample(val timestamp: Long, val magnitude: Double)
        private val samples = mutableListOf<Sample>()

        @Synchronized override fun onSensorChanged(event: SensorEvent) {
            val magnitude = hypot(hypot(event.values[0].toDouble(), event.values[1].toDouble()), event.values[2].toDouble())
            samples += Sample(event.timestamp, magnitude)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        @Synchronized fun summarize(pairs: List<Pairing>, available: Boolean, realtime: Boolean): String {
            if (!available || !realtime) return "gyroAvailable=$available cameraRealtime=$realtime"
            val covering = pairs.mapNotNull { pair ->
                val exposure = pair.frame.exposureNs ?: return@mapNotNull null
                val start = pair.motion.timestamp
                val before = samples.indexOfLast { it.timestamp <= start }
                val after = samples.indexOfFirst { it.timestamp >= start + exposure }
                if (before < 0 || after <= before) return@mapNotNull null
                val window = samples.subList(before, after + 1)
                if (window.zipWithNext().any { (a, b) -> b.timestamp - a.timestamp > 40_000_000L }) null
                else window.maxOf { it.magnitude }
            }
            return "gyroAvailable=true cameraRealtime=true gyroSamples=${samples.size} " +
                "gyroExposuresCovered=${covering.size}/${pairs.size} gyroMaxRadPerSec=${covering.maxOrNull()}"
        }
    }

    private class Measurements(private val route: Route, private val motionSize: Size) {
        private val images = Stream.entries.associateWith { linkedMapOf<Long, Buffer>() }
        private val frames = linkedMapOf<Long, Frame>()
        private val losses = mutableListOf<Loss>()
        private val failures = mutableListOf<Failure>()
        val baseline = CompletableFuture<List<Pairing>>()
        val shot = CompletableFuture<Shot>()
        val recovered = CompletableFuture<List<Pairing>>()

        @Synchronized fun image(stream: Stream, buffer: Buffer) {
            images.getValue(stream)[buffer.timestamp] = buffer
            update()
        }

        @Synchronized fun result(frame: Frame) {
            frames[frame.number] = frame
            update()
        }

        @Synchronized fun failed(frameNumber: Long, reason: Int, still: Boolean) {
            failures += Failure(frameNumber, reason)
            if (still) shot.completeExceptionally(IllegalStateException("jpeg_capture_failed:$reason"))
        }

        @Synchronized fun lost(stream: Stream?, frameNumber: Long) {
            losses += Loss(stream, frameNumber)
            if (stream == Stream.JPEG) shot.completeExceptionally(IllegalStateException("jpeg_buffer_lost"))
        }

        private fun update() {
            if (recovered.isDone) return
            if (!baseline.isDone) {
                val steady = pairs().drop(WARM_UP_FRAMES)
                if (steady.size >= BASELINE_FRAMES) baseline.complete(steady.take(BASELINE_FRAMES))
                return
            }
            if (!shot.isDone) {
                frames.values.firstOrNull { it.still }?.let { frame ->
                    val jpeg = frame.timestamps.firstNotNullOfOrNull { images.getValue(Stream.JPEG)[it] }
                    if (jpeg != null) shot.complete(Shot(frame, jpeg))
                }
            }
            val jpeg = if (shot.isCompletedExceptionally) null else shot.getNow(null)
            if (jpeg != null) {
                val after = pairs().filter {
                    it.frame.number > jpeg.frame.number && it.motion.arrival > jpeg.buffer.arrival &&
                        it.preview.arrival > jpeg.buffer.arrival
                }
                if (after.size >= RECOVERY_FRAMES) recovered.complete(after.take(RECOVERY_FRAMES))
            }
        }

        private fun pairs() = frames.values.asSequence().filter { !it.still && it.confirmedTele }.mapNotNull { frame ->
            frame.timestamps.firstNotNullOfOrNull { timestamp ->
                val preview = images.getValue(Stream.PREVIEW)[timestamp]?.takeIf {
                    it.size == PREVIEW_SIZE && it.format == ImageFormat.PRIVATE
                } ?: return@firstNotNullOfOrNull null
                val motion = images.getValue(Stream.MOTION)[timestamp]?.takeIf {
                    it.size == motionSize && it.format == ImageFormat.YUV_420_888
                } ?: return@firstNotNullOfOrNull null
                Pairing(frame, preview, motion)
            }
        }.sortedBy { it.frame.number }.toList()

        @Synchronized fun snapshot() = Snapshot(frames.values.toList(), images.mapValues { it.value.toMap() },
            recovered.getNow(emptyList()), losses.toList(), failures.toList())
    }

    private data class Snapshot(
        val frames: List<Frame>, val images: Map<Stream, Map<Long, Buffer>>, val recovered: List<Pairing>,
        val losses: List<Loss>, val failures: List<Failure>
    ) {
        fun aroundShot(baseline: List<Pairing>, shot: Shot, submitted: Long): String {
            val interval = baseline.first().frame.number..recovered.last().frame.number
            val expected = frames.filter { !it.still && it.number in interval }
            val lacking = listOf(Stream.PREVIEW, Stream.MOTION).associateWith { stream ->
                expected.count { frame -> frame.timestamps.none { it in images.getValue(stream) } }
            }
            val notPaired = expected.count { frame ->
                frame.timestamps.none { it in images.getValue(Stream.PREVIEW) && it in images.getValue(Stream.MOTION) }
            }
            val previewGap = peakGap(Stream.PREVIEW, submitted, shot.buffer.arrival)
            val yuvGap = peakGap(Stream.MOTION, submitted, shot.buffer.arrival)
            val ys = baseline.map { it.motion }.sortedBy { it.timestamp }
            val typical = percentile(ys.zipWithNext { a, b -> b.arrival - a.arrival }.filter { it >= 0 }, 0.95)
            val beforeStill = expected.lastOrNull { it.number < shot.frame.number }
            val afterStill = expected.firstOrNull { it.number > shot.frame.number }
            val beforeBuffer = beforeStill?.timestamps?.firstNotNullOfOrNull { images.getValue(Stream.MOTION)[it] }
            val afterBuffer = afterStill?.timestamps?.firstNotNullOfOrNull { images.getValue(Stream.MOTION)[it] }
            val spanningGap = if (beforeBuffer != null && afterBuffer != null) afterBuffer.arrival - beforeBuffer.arrival else null
            return "jpegWindowPreviewPeakDeliveryGapNs=$previewGap jpegWindowYuvPeakDeliveryGapNs=$yuvGap " +
                "yuvAcrossStillDeliveryGapNs=$spanningGap baselineYuvP95DeliveryGapNs=$typical " +
                "yuvAcrossStillBeyondTwoBaselineIntervals=" +
                (if (spanningGap != null && typical != null) spanningGap > typical * 2 + 8_000_000 else null) +
                " missingByRepeatTimestamp=$lacking unpairedRepeats=$notPaired " +
                "bufferLost=${losses.filter { it.frameNumber in interval }.groupingBy { it.stream }.eachCount()} " +
                "captureFailures=${failures.filter { it.frameNumber in interval }.groupingBy { it.reason }.eachCount()}"
        }

        private fun peakGap(stream: Stream, from: Long, until: Long) = images.getValue(stream).values
            .sortedBy { it.arrival }.zipWithNext().filter { (a, b) -> a.arrival <= until && b.arrival >= from }
            .maxOfOrNull { (a, b) -> b.arrival - a.arrival }
    }

    private class DeviceLease(private val handler: Handler) {
        private val opened = CompletableFuture<CameraDevice>()
        private val closed = CompletableFuture<Unit>()
        private var submitted = false
        private var current: CameraDevice? = null
        private var closing = false

        @SuppressLint("MissingPermission")
        fun open(manager: CameraManager): CameraDevice {
            manager.openCamera(LOGICAL_ID, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    current = camera
                    if (closing) camera.close() else opened.complete(camera)
                }
                override fun onDisconnected(camera: CameraDevice) = onError(camera, ERROR_CAMERA_DEVICE)
                override fun onError(camera: CameraDevice, error: Int) {
                    opened.completeExceptionally(IllegalStateException("camera_device_error:$error"))
                    camera.close()
                }
                override fun onClosed(camera: CameraDevice) { closed.complete(Unit) }
            }, handler)
            submitted = true
            return opened.get(4, TimeUnit.SECONDS)
        }

        fun closeFirst(releaseOutputs: () -> Unit): CompletableFuture<Void> {
            handler.post {
                closing = true
                current?.close()
                if (!submitted) closed.complete(Unit)
            }
            return closed.thenRun(releaseOutputs)
        }
    }

    private companion object {
        const val TAG = "HC_HIGH_ZOOM_MOTION"
        const val LOGICAL_ID = "0"
        const val TELE_ID = "6"
        const val WARM_UP_FRAMES = 6
        const val BASELINE_FRAMES = 24
        const val RECOVERY_FRAMES = 16
        val PREVIEW_SIZE = Size(1440, 1080)
        val MOTION_FALLBACK = Size(640, 480)

        @Suppress("DEPRECATION")
        fun frame(route: Route, result: TotalCaptureResult, still: Boolean): Frame {
            val physical = if (route.pinned) {
                if (Build.VERSION.SDK_INT >= 31)
                    result.physicalCameraTotalResults[TELE_ID] ?: result.physicalCameraResults[TELE_ID]
                else result.physicalCameraResults[TELE_ID]
            } else null
            val active = result[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID]
            val zoom = if (Build.VERSION.SDK_INT >= 30) result[CaptureResult.CONTROL_ZOOM_RATIO] else null
            val source = if (route.pinned) physical else result
            val focal = source?.get(CaptureResult.LENS_FOCAL_LENGTH)
            val timestamps = listOfNotNull(result[CaptureResult.SENSOR_TIMESTAMP], physical?.get(CaptureResult.SENSOR_TIMESTAMP))
                .filter { it > 0L }.distinct()
            val tele = focal != null && abs(focal - 7f) <= 0.05f &&
                (route.pinned || (active == TELE_ID && zoom != null && abs(zoom - 3f) <= 0.02f))
            return Frame(result.frameNumber, timestamps, still, active, focal, zoom, tele,
                if (route.pinned) "physicalResult6" else "activePhysicalId",
                source?.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                source?.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE) == CaptureResult.LENS_OPTICAL_STABILIZATION_MODE_ON,
                source?.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE) == CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }

        fun percentile(values: List<Long>, p: Double): Long? = if (values.isEmpty()) null
            else values.sorted()[ceil(values.size * p).toInt() - 1]

        fun cadence(buffers: List<Buffer>): String {
            val bySensor = buffers.sortedBy { it.timestamp }
            val sensor = bySensor.zipWithNext { a, b -> b.timestamp - a.timestamp }
            val arrival = buffers.sortedBy { it.arrival }.zipWithNext { a, b -> b.arrival - a.arrival }
            return "{sensorP50Ns=${percentile(sensor, 0.50)} sensorP95Ns=${percentile(sensor, 0.95)} " +
                "arrivalP50Ns=${percentile(arrival, 0.50)} arrivalP95Ns=${percentile(arrival, 0.95)}}"
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
