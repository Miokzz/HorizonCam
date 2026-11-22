package com.miokzz.horizoncam

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit field probe. Acquires and closes YUV images without accessing their planes. */
class MultiCameraFieldTest {
    @Test fun probeLogicalAndSimultaneousPhysicalOutputs() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("multiCameraProbe") == "yes")
        assumeTrue(Build.MODEL == "SM-S9210")
        val runner = InstrumentationRegistry.getInstrumentation()
        val manager = runner.targetContext.getSystemService(CameraManager::class.java)
        val visible = manager.cameraIdList.sorted()
        val concurrent = if (Build.VERSION.SDK_INT >= 30) {
            manager.concurrentCameraIds.map { it.sorted().joinToString("+") }.sorted().toString()
        } else "query_unavailable"
        report("device=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} visible=$visible concurrentDevices=$concurrent")

        val logical = manager.getCameraCharacteristics(LOGICAL)
        val members = logical.physicalCameraIds.sorted()
        val sync = when (logical[CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE]) {
            CameraMetadata.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_APPROXIMATE -> "APPROXIMATE"
            CameraMetadata.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_CALIBRATED -> "CALIBRATED"
            else -> "UNREPORTED"
        }
        report("logical=$LOGICAL physicalMembers=$members sync=$sync")
        val characteristics = (visible + LOGICAL + TARGETS).distinct().mapNotNull { id ->
            runCatching { manager.getCameraCharacteristics(id) }.fold(
                onSuccess = { chars -> reportCharacteristics(id, chars); id to chars },
                onFailure = { error -> report("id=$id characteristics=unavailable error=${describe(error)}"); null }
            )
        }.toMap()
        val allSizes = (listOf(LOGICAL) + TARGETS).map { yuvSizes(characteristics[it]) }
        val common = allSizes.reduce { a, b -> a intersect b }
        val logicalSize = chooseSize(common) ?: chooseSize(allSizes.first())
        val sameSize = chooseSize(common)
        report("format=YUV_420_888 common4by3=${common.sortedBy { it.width }} logicalSize=$logicalSize simultaneousSize=$sameSize")
        val thread = HandlerThread("hc-multi-camera-probe").apply { start() }
        var closeAcknowledged = true
        try {
            val handler = Handler(thread.looper)
            if (logicalSize != null) {
                scenario(manager, handler, "logical", listOf(LOGICAL), logicalSize, pinned = false) { session ->
                    val zoomRange = zoomRange(logical)
                    for (zoom in listOf(0.6f, 1f, 3f)) {
                        if (zoomRange == null || !zoomRange.contains(zoom)) {
                            report("logical zoomRequested=$zoom status=unavailable advertisedRange=$zoomRange")
                            continue
                        }
                        val actual = session.sample(zoom)
                        reportLogical(zoom, actual)
                    }
                }
            } else report("session=logical status=unavailable reason=no_advertised_4by3_yuv")
            for (ids in listOf(listOf("2", "5"), listOf("5", "6"), listOf("2", "6"), TARGETS)) {
                val name = ids.joinToString("+")
                if (sameSize == null || !members.containsAll(ids)) {
                    report("session=$name status=not_attempted reason=no_common_4by3_yuv_or_missing_physical_member")
                    continue
                }
                scenario(manager, handler, name, ids, sameSize, pinned = true) { session ->
                    reportPhysical(name, ids, session.sample(null))
                }
            }
        } catch (error: CloseUnconfirmed) {
            closeAcknowledged = false
            // The corresponding onClosed callback still owns and will release its readers.
            error.cleanup.whenComplete { _, _ -> thread.quitSafely() }
            throw error
        } finally {
            if (closeAcknowledged) {
                thread.quitSafely()
                thread.join(1_000L)
            }
        }
    }

    private fun reportCharacteristics(id: String, chars: CameraCharacteristics) {
        val map = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        val ae = chars[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]?.toList().orEmpty()
        val lens = chars[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.toList().orEmpty()
        val timestampSource = chars[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE]
        report("id=$id focalMm=$lens zoomRange=${zoomRange(chars)} aeFps=$ae timestampSource=$timestampSource")
        if (map == null || (id != LOGICAL && id !in TARGETS)) return
        for ((name, format) in listOf("YUV" to ImageFormat.YUV_420_888, "JPEG" to ImageFormat.JPEG,
            "PRIVATE" to ImageFormat.PRIVATE)) {
            val sizes = map.getOutputSizes(format)?.toSet().orEmpty()
            val selected = (sizes.intersect(VIDEO_SIZES) + listOfNotNull(
                sizes.maxByOrNull(::pixels), sizes.filter(::isFourByThree).maxByOrNull(::pixels),
                chooseSize(sizes)
            )).sortedBy(::pixels)
            val limits = selected.joinToString(",") { size ->
                val minNs = runCatching { map.getOutputMinFrameDuration(format, size) }.getOrNull()
                val stallNs = runCatching { map.getOutputStallDuration(format, size) }.getOrNull()
                "${size}/minNs=$minNs/stallNs=$stallNs"
            }
            report("id=$id format=$name advertisedSizes=${sizes.size} selected=$limits")
        }
        val video = map.getOutputSizes(MediaRecorder::class.java)?.toSet().orEmpty()
        val regular = video.intersect(VIDEO_SIZES).sortedBy(::pixels).joinToString(",") { size ->
            val minNs = runCatching { map.getOutputMinFrameDuration(MediaRecorder::class.java, size) }.getOrNull()
            "${size}/minNs=$minNs"
        }
        val highSpeed = runCatching {
            map.highSpeedVideoSizes.orEmpty().sortedBy(::pixels).joinToString(",") { size ->
                "${size}/fps=${map.getHighSpeedVideoFpsRangesFor(size).toList()}"
            }
        }.getOrElse { "unavailable:${describe(it)}" }
        report("id=$id mediaRecorderAdvertisedSizes=${video.size} regularSelected=$regular constrainedHighSpeed=$highSpeed")
    }

    private fun scenario(
        manager: CameraManager,
        handler: Handler,
        name: String,
        ids: List<String>,
        size: Size,
        pinned: Boolean,
        measure: (ActiveSession) -> Unit
    ) {
        val lease = DeviceLease(handler)
        val outputs = mutableListOf<Stream>()
        try {
            val device = lease.open(manager)
            ids.forEach { id -> outputs += Stream(id, ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)) }
            val active = ActiveSession(device, handler, outputs)
            val config = active.configuration(pinned)
            val query = runCatching { device.isSessionConfigurationSupported(config) }
            report("session=$name size=$size supportQuery=${query.getOrNull() ?: describe(query.exceptionOrNull())}")
            if (query.getOrNull() == false) {
                report("session=$name status=not_attempted reason=framework_rejected_configuration")
                return
            }
            device.createCaptureSession(config)
            active.awaitConfigured()
            measure(active)
        } catch (error: Exception) {
            report("session=$name status=failed error=${describe(error)} halError=${lease.error.get()}")
        } finally {
            val tookMs = lease.closeFirst(outputs.map { it.reader })
            report("session=$name deviceCloseMs=$tookMs closeAcknowledged=true")
        }
    }

    private fun reportLogical(zoom: Float, sample: Sample) {
        val frames = sample.matched(LOGICAL, pinned = false)
        val recent = frames.takeLast(6)
        val last = recent.lastOrNull()?.logical
        val ids = frames.groupingBy { it.activeId ?: "unreported" }.eachCount()
        report("logical zoomRequested=$zoom zoomReported=${last?.zoom} activeCounts=$ids activeLast=${recent.lastOrNull()?.activeId} " +
            "focalMm=${last?.focal} crop=${last?.crop} aeFps=${last?.aeFps} " +
            "frames=${sample.frames.size} matched=${frames.size} images=${sample.images[LOGICAL].orEmpty().size} " +
            "cadenceFps=${cadence(frames.mapNotNull { it.logical.timestamp })} captureFailures=${sample.captureFailures} " +
            "status=${if (frames.isEmpty()) "unverified" else if (sample.complete) "delivered" else "partial"}")
    }

    private fun reportPhysical(name: String, ids: List<String>, sample: Sample) {
        val matched = ids.associateWith { sample.matched(it, pinned = true) }
        for (id in ids) {
            val values = matched.getValue(id)
            val data = values.lastOrNull()?.physical?.get(id)
            val images = sample.images[id].orEmpty()
            report("session=$name output=$id images=${images.size} firstNs=${images.firstOrNull()?.timestamp} " +
                "lastNs=${images.lastOrNull()?.timestamp} size=${images.firstOrNull()?.size} sensorResults=${sample.frames.count { id in it.physical }} " +
                "matched=${values.size} focalMm=${data?.focal} crop=${data?.crop} aeFps=${data?.aeFps} " +
                "cadenceFps=${cadence(images.map { it.timestamp })}")
        }
        val frameNumbers = matched.mapValues { (_, results) -> results.mapTo(mutableSetOf()) { it.number } }
        val pairedTimes = sample.frames.mapNotNull { frame ->
            if (!ids.all { frame.number in frameNumbers.getValue(it) }) return@mapNotNull null
            val timestamps = ids.map { frame.physical.getValue(it).timestamp ?: return@mapNotNull null }
            timestamps.maxOrNull()!! - timestamps.minOrNull()!!
        }.sorted()
        val sustained = pairedTimes.size >= MINIMUM_MATCHES
        report("session=$name status=${if (sustained) "delivered" else if (pairedTimes.isNotEmpty()) "partial" else "unverified"} " +
            "pairedFrames=${pairedTimes.size} minSkewNs=${pairedTimes.firstOrNull()} medianSkewNs=${pairedTimes.getOrNull(pairedTimes.size / 2)} " +
            "maxSkewNs=${pairedTimes.lastOrNull()} boundedWaitComplete=${sample.complete} captureFailures=${sample.captureFailures}")
    }

    private class ActiveSession(private val device: CameraDevice, private val handler: Handler, private val outputs: List<Stream>) {
        private val configured = CompletableFuture<CameraCaptureSession>()
        private lateinit var session: CameraCaptureSession

        fun configuration(pinned: Boolean) = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs.map { stream ->
            OutputConfiguration(stream.reader.surface).apply {
                if (pinned) setPhysicalCameraId(stream.id)
                if (Build.VERSION.SDK_INT >= 33) timestampBase = OutputConfiguration.TIMESTAMP_BASE_SENSOR
            }
        }, { handler.post(it) }, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) { configured.complete(session) }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                configured.completeExceptionally(IllegalStateException("HAL recusou createCaptureSession"))
            }
        })

        fun awaitConfigured() {
            session = configured.get(3, TimeUnit.SECONDS)
        }

        fun sample(zoom: Float?): Sample {
            val recorder = Recorder(outputs.map { it.id }, pinned = zoom == null)
            outputs.forEach { stream ->
                stream.reader.setOnImageAvailableListener({ reader ->
                    reader.acquireNextImage()?.use { image ->
                        recorder.image(stream.id, ImageSample(image.timestamp, Size(image.width, image.height)))
                    }
                }, handler)
            }
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                outputs.forEach { addTarget(it.reader.surface) }
                if (Build.VERSION.SDK_INT >= 30 && zoom != null) set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
            }.build()
            session.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    recorder.result(result)
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    recorder.captureFailed()
                }
            }, handler)
            val complete = recorder.ready.await(1_700L, TimeUnit.MILLISECONDS)
            return recorder.snapshot(complete)
        }
    }

    /** Keeps outputs alive until CameraDevice.onClosed even after a configure timeout or failure. */
    private class DeviceLease(private val handler: Handler) {
        private val opening = CompletableFuture<CameraDevice>()
        private val closed = CompletableFuture<Unit>()
        private val current = AtomicReference<CameraDevice>()
        private val closing = AtomicBoolean()
        private var submitted = false
        val error = AtomicReference<Int>()

        @SuppressLint("MissingPermission")
        fun open(manager: CameraManager): CameraDevice {
            manager.openCamera(LOGICAL, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    current.set(camera)
                    if (closing.get()) camera.close() else opening.complete(camera)
                }

                override fun onDisconnected(camera: CameraDevice) = onError(camera, ERROR_CAMERA_DEVICE)

                override fun onError(camera: CameraDevice, code: Int) {
                    error.set(code)
                    opening.completeExceptionally(IllegalStateException("CameraDevice error=$code"))
                    camera.close()
                }

                override fun onClosed(camera: CameraDevice) { closed.complete(Unit) }
            }, handler)
            submitted = true
            return opening.get(3, TimeUnit.SECONDS)
        }

        fun closeFirst(readers: List<ImageReader>): Long {
            val begin = SystemClock.elapsedRealtime()
            closing.set(true)
            current.get()?.close()
            if (!submitted) closed.complete(Unit)
            val cleanup = closed.thenRun { readers.forEach(ImageReader::close) }
            try { cleanup.get(4, TimeUnit.SECONDS) }
            catch (error: TimeoutException) { throw CloseUnconfirmed(cleanup, error) }
            return SystemClock.elapsedRealtime() - begin
        }
    }

    private class CloseUnconfirmed(val cleanup: CompletableFuture<Void>, cause: Throwable) :
        IllegalStateException("Device sem onClosed confirmado; demais cenários cancelados.", cause)

    private data class Stream(val id: String, val reader: ImageReader)
    private data class ImageSample(val timestamp: Long, val size: Size)
    private data class SensorSample(
        val timestamp: Long?,
        val focal: Float?,
        val zoom: Float?,
        val crop: String?,
        val aeFps: String?
    )
    private data class Frame(val number: Long, val activeId: String?, val logical: SensorSample, val physical: Map<String, SensorSample>)

    private data class Sample(
        val images: Map<String, List<ImageSample>>,
        val frames: List<Frame>,
        val complete: Boolean,
        val captureFailures: Int
    ) {
        fun matched(id: String, pinned: Boolean): List<Frame> {
            val times = images[id].orEmpty().mapTo(mutableSetOf()) { it.timestamp }
            return frames.filter { frame ->
                val timestamp = if (pinned) frame.physical[id]?.timestamp else frame.logical.timestamp
                timestamp != null && timestamp in times
            }
        }
    }

    private class Recorder(private val ids: List<String>, private val pinned: Boolean) {
        private val images = ids.associateWith { mutableListOf<ImageSample>() }
        private val frames = mutableListOf<Frame>()
        private var failures = 0
        val ready = CountDownLatch(1)

        @Synchronized fun image(id: String, frame: ImageSample) {
            val list = images.getValue(id)
            if (list.size < MAX_FRAMES) list += frame
            checkReady()
        }

        @Suppress("DEPRECATION")
        @Synchronized fun result(result: TotalCaptureResult) {
            if (frames.size >= MAX_FRAMES) return
            val physical = if (Build.VERSION.SDK_INT >= 31) result.physicalCameraTotalResults
                .ifEmpty { result.physicalCameraResults }
            else result.physicalCameraResults
            frames += Frame(result.frameNumber, result[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID],
                sensor(result), physical.mapValues { (_, value) -> sensor(value) })
            checkReady()
        }

        @Synchronized fun captureFailed() { failures++ }

        @Synchronized fun snapshot(complete: Boolean) =
            Sample(images.mapValues { (_, value) -> value.toList() }, frames.toList(), complete, failures)

        private fun checkReady() {
            if (ready.count == 0L || frames.size < MINIMUM_MATCHES || images.values.any { it.size < MINIMUM_MATCHES }) return
            val sample = Sample(images, frames, false, failures)
            if (pinned) {
                val common = ids.map { id -> sample.matched(id, pinned = true).mapTo(mutableSetOf()) { it.number } }
                    .reduce { a, b -> a.apply { retainAll(b) } }
                if (common.size >= MINIMUM_MATCHES) ready.countDown()
            } else {
                val matched = sample.matched(LOGICAL, pinned = false)
                val recent = matched.takeLast(6)
                if (matched.size >= 12 && recent.map { it.activeId to it.logical.focal }.distinct().size == 1) {
                    ready.countDown()
                }
            }
        }
    }

    private companion object {
        const val LOGICAL = "0"
        val TARGETS = listOf("2", "5", "6")
        val VIDEO_SIZES = setOf(Size(1280, 720), Size(1920, 1080), Size(3840, 2160), Size(1440, 1080))
        const val MINIMUM_MATCHES = 6
        const val MAX_FRAMES = 96
        const val TAG = "HC_MULTI"

        fun report(value: String) {
            val line = "$TAG $value"
            Log.i(TAG, value)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\n$line\n") })
        }

        fun zoomRange(chars: CameraCharacteristics) =
            if (Build.VERSION.SDK_INT >= 30) chars[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE] else null

        fun yuvSizes(chars: CameraCharacteristics?): Set<Size> =
            chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)?.filter(::isFourByThree)?.toSet().orEmpty()

        fun isFourByThree(size: Size) = size.width.toLong() * 3 == size.height.toLong() * 4
        fun pixels(size: Size) = size.width.toLong() * size.height
        fun chooseSize(sizes: Set<Size>) =
            listOf(Size(1440, 1080), Size(1280, 960), Size(640, 480)).firstOrNull(sizes::contains)
                ?: sizes.filter(::isFourByThree).minByOrNull { kotlin.math.abs(pixels(it) - 1440L * 1080L) }

        fun sensor(result: CaptureResult) = SensorSample(result[CaptureResult.SENSOR_TIMESTAMP],
            result[CaptureResult.LENS_FOCAL_LENGTH],
            if (Build.VERSION.SDK_INT >= 30) result[CaptureResult.CONTROL_ZOOM_RATIO] else null,
            result[CaptureResult.SCALER_CROP_REGION]?.toShortString(),
            result[CaptureResult.CONTROL_AE_TARGET_FPS_RANGE]?.toString())

        fun cadence(timestamps: List<Long>): String {
            val intervals = timestamps.distinct().sorted().zipWithNext { earlier, later -> later - earlier }.sorted()
            val period = intervals.getOrNull(intervals.size / 2) ?: return "unverified"
            return String.format(Locale.ROOT, "%.2f", 1_000_000_000.0 / period)
        }

        fun describe(error: Throwable?): String {
            val root = error?.cause ?: error ?: return "unreported"
            return "${root.javaClass.simpleName}:${root.message.orEmpty().replace('\n', ' ')}"
        }
    }
}
