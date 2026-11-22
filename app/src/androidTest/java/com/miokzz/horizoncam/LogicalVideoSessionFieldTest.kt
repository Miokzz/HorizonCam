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
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Field-only capability measurement. It encodes AVC to memory without audio, EIS or an MP4. */
class LogicalVideoSessionFieldTest {
    private data class Mode(val size: Size, val fps: Int, val bitRate: Int) {
        val label get() = "${size.width}x${size.height}@$fps"
    }

    private data class CameraFrame(
        val tag: Int,
        val at: Long,
        val sensorTimestamp: Long?,
        val appliedZoom: Float?,
        val activePhysical: String?,
        val focalMm: Float?,
        val physicalCrop: String?,
        val aeRange: Range<Int>?
    )

    private data class EncodedFrame(val at: Long, val ptsUs: Long)
    private data class Cadence(val count: Int, val fps: Double, val maxGapMs: Double, val longGaps: Int) {
        fun summary() = "$count/${decimal(fps)}fps/maxGap=${decimal(maxGapMs)}ms/longGaps=$longGaps"
    }

    private data class Outcome(val live: Boolean, val stalled: Boolean, val monotonicPts: Boolean)

    @Test
    fun probesLogicalVideoRecordingZoom() {
        val runner = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("logicalVideoProbe") == "yes")
        assumeTrue(Build.MODEL == "SM-S9210")
        val manager = runner.targetContext.getSystemService(CameraManager::class.java)
        val info = manager.getCameraCharacteristics(CAMERA_ID)
        val zoomRange = info[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]
            ?: throw AssertionError("A câmera lógica 0 não anunciou CONTROL_ZOOM_RATIO_RANGE.")
        val fpsRanges = info[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES].orEmpty()
        val outputs = checkNotNull(info[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP])
        val previewSize = choosePreviewSize(outputs.getOutputSizes(ImageFormat.PRIVATE).orEmpty())
        fun report(line: String) {
            Log.i(TAG, line)
            runner.sendStatus(0, Bundle().apply { putString("stream", "\n$line\n") })
        }

        report("logicalVideoProbe camera=$CAMERA_ID model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
            "physical=${info.physicalCameraIds.sorted()} zoomRange=$zoomRange fpsRanges=${fpsRanges.toList()} " +
            "preview=$previewSize encoder=AVC eis=off audio=off export=none")
        val modes = listOf(
            Mode(Size(1920, 1080), 30, 8_000_000),
            Mode(Size(1920, 1080), 60, 14_000_000),
            Mode(Size(3840, 2160), 30, 22_000_000),
            Mode(Size(3840, 2160), 60, 35_000_000)
        )
        val outcomes = modes.map { mode ->
            val fpsRange = fpsRanges.filter { it.upper == mode.fps }.maxByOrNull { it.lower }
            if (fpsRange == null) {
                report("mode=${mode.label} status=NOT_ADVERTISED_AE_RANGE")
                return@map mode to Outcome(live = false, stalled = false, monotonicPts = true)
            }
            val minDuration = runCatching { outputs.getOutputMinFrameDuration(MediaCodec::class.java, mode.size) }.getOrNull()
            report("mode=${mode.label} requestedAe=$fpsRange " +
                "encoderSurfaceMinFrameMs=${minDuration?.let { decimal(it / 1e6) } ?: "unknown"}")
            mode to probe(manager, mode, previewSize, zoomRange, fpsRange, ::report)
        }
        assertTrue("O controle FHD30 não entregou imagens de prévia e frames codificados.",
            outcomes.first().second.live)
        val regressions = outcomes.filter { (_, result) -> result.stalled || !result.monotonicPts }
        assertTrue("Travamento após atingir fluxo ou PTS não monotônicos: ${regressions.map { it.first.label }}",
            regressions.isEmpty())
    }

    private fun probe(
        manager: CameraManager, mode: Mode, previewSize: Size, zoomRange: Range<Float>, fpsRange: Range<Int>,
        report: (String) -> Unit
    ): Outcome {
        val thread = HandlerThread("logical-video-${mode.size.height}-${mode.fps}").apply { start() }
        val handler = Handler(thread.looper)
        val lease = DeviceLease(manager, handler)
        val previews = ConcurrentLinkedQueue<Long>()
        val cameraFrames = ConcurrentLinkedQueue<CameraFrame>()
        val captureFailures = AtomicInteger()
        val previewFailures = AtomicInteger()
        val encoded = mutableListOf<EncodedFrame>()
        var codec: MediaCodec? = null
        var codecStarted = false
        var input: Surface? = null
        var preview: ImageReader? = null
        var live = false
        var stalled = false
        var stage = "encoder"
        var outputFormat = "missing"
        var codecName = "missing"
        var status = "INCONCLUSIVE"
        fun releaseOutputs() {
            preview?.setOnImageAvailableListener(null, null)
            preview?.close()
            if (codecStarted) runCatching { codec?.stop() }
            input?.release()
            codec?.release()
        }

        try {
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { codec = it }
            codecName = encoder.name
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, mode.size.width, mode.size.height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, mode.bitRate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, mode.fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderSurface = encoder.createInputSurface().also { input = it }
            encoder.start()
            codecStarted = true

            stage = "preview"
            val reader = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.PRIVATE, 4,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE).also { preview = it }
            reader.setOnImageAvailableListener({ source ->
                try {
                    source.acquireNextImage()?.use { previews.add(SystemClock.elapsedRealtimeNanos()) }
                } catch (_: IllegalStateException) {
                    previewFailures.incrementAndGet()
                }
            }, handler)

            stage = "open"
            val device = lease.open()
            stage = "configure"
            val configured = CompletableFuture<CameraCaptureSession>()
            val output = listOf(reader.surface, encoderSurface).map { surface ->
                OutputConfiguration(surface).apply {
                    if (Build.VERSION.SDK_INT >= 33) timestampBase = OutputConfiguration.TIMESTAMP_BASE_SENSOR
                }
            }
            val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, output,
                Executor { command -> handler.post(command) }, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) { configured.complete(session) }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        configured.completeExceptionally(IllegalStateException("CameraDevice recusou a combinação."))
                    }
                })
            config.sessionParameters = videoRequest(device, fpsRange).build()
            val query = runCatching { device.isSessionConfigurationSupported(config) }
                .fold({ it.toString() }, { "unavailable:${it.javaClass.simpleName}" })
            report("mode=${mode.label} configQuery=$query timestampBase=${if (Build.VERSION.SDK_INT >= 33) "sensor" else "default"}")
            // Try the real configuration even when the optional query is inconclusive.
            device.createCaptureSession(config)
            val cameraSession = configured.get(CAMERA_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
                ) {
                    val tag = request.tag as? Int ?: return
                    val active = result[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID]
                    val focal = if (Build.VERSION.SDK_INT >= 31 && active != null) {
                        result.physicalCameraTotalResults[active]?.get(CaptureResult.LENS_FOCAL_LENGTH)
                    } else null
                    val crop = if (Build.VERSION.SDK_INT >= 35) {
                        result[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION]?.toShortString()
                    } else null
                    cameraFrames.add(CameraFrame(tag, SystemClock.elapsedRealtimeNanos(),
                        result[CaptureResult.SENSOR_TIMESTAMP], result[CaptureResult.CONTROL_ZOOM_RATIO], active,
                        focal ?: result[CaptureResult.LENS_FOCAL_LENGTH], crop,
                        result[CaptureResult.CONTROL_AE_TARGET_FPS_RANGE]))
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    captureFailures.incrementAndGet()
                }
            }
            val buffer = MediaCodec.BufferInfo()
            fun pump() {
                lease.failure.get()?.let { throw IllegalStateException(it) }
                when (val index = encoder.dequeueOutputBuffer(buffer, 15_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val actual = encoder.outputFormat
                        outputFormat = "${actual.getInteger(MediaFormat.KEY_WIDTH)}x${actual.getInteger(MediaFormat.KEY_HEIGHT)}" +
                            "@${actual.getNumber(MediaFormat.KEY_FRAME_RATE, 0)}"
                    }
                    else -> if (index >= 0) {
                        try {
                            if (buffer.size > 0 && buffer.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                encoded.add(EncodedFrame(SystemClock.elapsedRealtimeNanos(), buffer.presentationTimeUs))
                            }
                        } finally { encoder.releaseOutputBuffer(index, false) }
                    }
                }
            }

            stage = "stream"
            for ((tag, zoom) in listOf(0.6f, 1f, 3f).withIndex()) {
                if (!zoomRange.contains(zoom)) {
                    report("mode=${mode.label} zoom=$zoom status=OUTSIDE_ZOOM_RANGE")
                    continue
                }
                val request = videoRequest(device, fpsRange).apply {
                    addTarget(reader.surface)
                    addTarget(encoderSurface)
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                    setTag(tag)
                }.build()
                val commandAt = SystemClock.elapsedRealtimeNanos()
                val sequence = cameraSession.setRepeatingRequest(request, callback, handler)
                // Drain through every change. Only the settled wall-clock window is used for FPS.
                val settleMs = if (live) 400L else 900L
                while (SystemClock.elapsedRealtimeNanos() < commandAt + settleMs * NS_PER_MS) pump()
                val stableAt = SystemClock.elapsedRealtimeNanos()
                val end = stableAt + 1_200L * NS_PER_MS
                while (SystemClock.elapsedRealtimeNanos() < end) pump()
                val stableCamera = cameraFrames.filter { it.tag == tag && it.at >= stableAt && it.at < end }
                val stablePreviews = previews.filter { it in stableAt until end }
                val stableEncoded = encoded.filter { it.at in stableAt until end }
                val first = cameraFrames.firstOrNull { it.tag == tag }
                val firstApplied = cameraFrames.firstOrNull {
                    it.tag == tag && it.appliedZoom?.let { actual -> abs(actual - zoom) <= zoom * 0.02f } == true
                }
                val cameraCadence = cadence(stableCamera.mapNotNull { it.sensorTimestamp }, mode.fps)
                val previewCadence = cadence(stablePreviews, mode.fps)
                val encodedCadence = cadence(stableEncoded.map { it.ptsUs * 1_000L }, mode.fps)
                val streaming = minOf(cameraCadence.count, previewCadence.count, encodedCadence.count) >= 2
                val hadFlow = live
                live = live || streaming
                stalled = stalled || hadFlow && !streaming ||
                    maxOf(cameraCadence.maxGapMs, previewCadence.maxGapMs, encodedCadence.maxGapMs) >= 750.0
                val actualFps = minOf(cameraCadence.fps, previewCadence.fps, encodedCadence.fps)
                val phaseStatus = when {
                    !streaming -> "NO_SUSTAINED_FLOW"
                    actualFps < mode.fps * 0.90 -> "LOWER_FPS"
                    maxOf(cameraCadence.longGaps, previewCadence.longGaps, encodedCadence.longGaps) > 0 -> "HITCH"
                    else -> "AT_REQUESTED_CADENCE"
                }
                val matchedPts = stableCamera.mapNotNull { it.sensorTimestamp?.div(1_000L) }.toHashSet()
                report("mode=${mode.label} zoom=$zoom seq=$sequence status=$phaseStatus " +
                    "physical=${stableCamera.groupingBy { it.activePhysical ?: "unknown" }.eachCount()} " +
                    "focalMm=${stableCamera.map { it.focalMm }.distinct()} observedZoom=${stableCamera.map { it.appliedZoom }.distinct()} " +
                    "physicalCrop=${stableCamera.map { it.physicalCrop }.distinct()} " +
                    "appliedAe=${stableCamera.map { it.aeRange }.distinct()} firstResultMs=${since(first, commandAt)} " +
                    "firstAppliedMs=${since(firstApplied, commandAt)} camera=${cameraCadence.summary()} preview=${previewCadence.summary()} " +
                    "avc=${encodedCadence.summary()} sensorTimestampPtsMatches=${stableEncoded.count { it.ptsUs in matchedPts }}/${stableEncoded.size}")
            }

            status = if (live) "LIVE" else "NO_SUSTAINED_FLOW"
        } catch (error: Exception) {
            stalled = stalled || live
            val cause = error.cause ?: error
            status = "${if (live) "INTERRUPTED" else "REJECTED"}_${stage.uppercase(Locale.US)}:${cause.javaClass.simpleName}:${cause.message}"
        } finally {
            val closeStart = SystemClock.elapsedRealtimeNanos()
            try {
                lease.closeAndAwait()
            } catch (error: TimeoutException) {
                // Abort the entire probe. Keep the outputs attached until the late onClosed.
                lease.afterClosed { releaseOutputs(); thread.quitSafely() }
                throw AssertionError("mode=${mode.label}: sem onClosed após fechar a câmera; ensaio interrompido.").apply {
                    initCause(error)
                }
            }
            val closeMs = (SystemClock.elapsedRealtimeNanos() - closeStart) / 1e6
            try {
                releaseOutputs()
            } finally {
                thread.quitSafely()
                thread.join(1_000L)
            }
            report("mode=${mode.label} cameraDeviceCloseMs=${decimal(closeMs)} " +
                "closeAndReleaseMs=${decimal((SystemClock.elapsedRealtimeNanos() - closeStart) / 1e6)}")
        }
        val monotonic = encoded.zipWithNext().all { (a, b) -> b.ptsUs > a.ptsUs }
        val aggregateCamera = cadence(cameraFrames.mapNotNull { it.sensorTimestamp }, mode.fps)
        val aggregateEncoded = cadence(encoded.map { it.ptsUs * 1_000L }, mode.fps)
        val encodedArrivals = cadence(encoded.map { it.at }, mode.fps)
        val aggregatePreview = cadence(previews.toList(), mode.fps)
        val hadGap = maxOf(aggregateCamera.maxGapMs, aggregateEncoded.maxGapMs,
            encodedArrivals.maxGapMs, aggregatePreview.maxGapMs) >= 750.0
        stalled = stalled || live && hadGap
        report("mode=${mode.label} result=$status codec=$codecName actualFormat=$outputFormat " +
            "monotonicPts=$monotonic stallOver750ms=$stalled camera=${aggregateCamera.summary()} " +
            "previewArrival=${aggregatePreview.summary()} avcPts=${aggregateEncoded.summary()} avcArrival=${encodedArrivals.summary()} " +
            "captureFailures=${captureFailures.get()} previewFailures=${previewFailures.get()}")
        return Outcome(live, stalled, monotonic)
    }

    private fun videoRequest(device: CameraDevice, fpsRange: Range<Int>) =
        device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }

    private class DeviceLease(private val manager: CameraManager, private val handler: Handler) :
        CameraDevice.StateCallback() {
        private val opening = CompletableFuture<CameraDevice>()
        private val closed = CompletableFuture<Unit>()
        private val active = AtomicReference<CameraDevice?>()
        private val closeRequested = AtomicBoolean()
        val failure = AtomicReference<String?>()
        private var submitted = false

        @SuppressLint("MissingPermission")
        fun open(): CameraDevice {
            manager.openCamera(CAMERA_ID, this, handler)
            submitted = true
            return opening.get(CAMERA_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }

        override fun onOpened(camera: CameraDevice) {
            active.set(camera)
            if (closeRequested.get()) camera.close() else opening.complete(camera)
        }

        override fun onDisconnected(camera: CameraDevice) = abandon(camera, "CameraDevice desconectou.")

        override fun onError(camera: CameraDevice, error: Int) = abandon(camera, "CameraDevice error=$error")

        override fun onClosed(camera: CameraDevice) {
            active.compareAndSet(camera, null)
            closed.complete(Unit)
        }

        private fun abandon(camera: CameraDevice, detail: String) {
            failure.set(detail)
            opening.completeExceptionally(IllegalStateException(detail))
            camera.close()
        }

        fun closeAndAwait() {
            if (!submitted) return
            closeRequested.set(true)
            // Do not close the session or detach either output before CameraDevice.onClosed.
            active.get()?.close()
            closed.get(CAMERA_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }

        fun afterClosed(release: () -> Unit) {
            closed.thenRun { release() }
        }
    }

    private companion object {
        const val CAMERA_ID = "0"
        const val TAG = "HorizonCamVideoProbe"
        const val CAMERA_TIMEOUT_MS = 5_000L
        const val NS_PER_MS = 1_000_000L

        fun choosePreviewSize(sizes: Array<out Size>): Size {
            require(sizes.isNotEmpty()) { "A câmera lógica não anunciou saída de prévia PRIVATE." }
            val desired = Size(1280, 720)
            return sizes.firstOrNull { it == desired }
                ?: sizes.filter { it.width <= 1920 && it.height <= 1080 && it.width * 9 == it.height * 16 }
                    .minByOrNull { abs(it.width - desired.width) }
                ?: sizes.minBy { it.width.toLong() * it.height }
        }

        fun cadence(samples: List<Long>, fps: Int): Cadence {
            val gaps = samples.zipWithNext { a, b -> (b - a) / 1e6 }
            val spanMs = gaps.sum()
            val measured = if (spanMs > 0) gaps.size * 1_000.0 / spanMs else 0.0
            return Cadence(samples.size, measured, gaps.maxOrNull() ?: 0.0,
                gaps.count { it > 2_000.0 / fps + 1.0 })
        }

        fun decimal(value: Double) = String.format(Locale.US, "%.2f", value)

        fun since(frame: CameraFrame?, at: Long) = frame?.let { decimal((it.at - at) / 1e6) } ?: "none"
    }
}
