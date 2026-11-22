package com.miokzz.horizoncam.photo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.miokzz.horizoncam.ui.PreviewFitTransform
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Direct Camera2 owner for the S24's fixed 0/6 JPEG route. The view, public controls,
 * and callbacks belong to main. Camera operations and JPEG I/O use one serial handler.
 */
internal class PhysicalTelePhotoSession(
    context: Context,
    private val preview: TextureView,
    private val rotation: Int,
    private val onReady: (Evidence) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onCaptureIdle: () -> Unit = {}
) : TextureView.SurfaceTextureListener {
    data class Evidence(val physicalCameraId: String, val focalLengthMm: Float, val cropZoom: Float)

    private class Capabilities(val logical: CameraCharacteristics, val physical: CameraCharacteristics) {
        val sensorOrientation = checkNotNull(physical[CameraCharacteristics.SENSOR_ORIENTATION])
        val surfaceOrientation = logical[CameraCharacteristics.SENSOR_ORIENTATION] ?: sensorOrientation
        val sensorArray = checkNotNull(physical[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE])
        val logicalArray = logical[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE] ?: sensorArray
        val physicalKeys = logical.availablePhysicalCameraRequestKeys.orEmpty().toSet()
        val requestKeys = logical.availableCaptureRequestKeys.toSet()
        val afModes = physical[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
        val autofocus = CaptureRequest.CONTROL_AF_MODE_AUTO in afModes
        val manualFocus = CaptureRequest.CONTROL_AF_MODE_OFF in afModes &&
            (physical[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f) > 0f &&
            CaptureRequest.LENS_FOCUS_DISTANCE in requestKeys
        val continuousFocus = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes
        val afRegions = (physical[CameraCharacteristics.CONTROL_MAX_REGIONS_AF] ?: 0) > 0
        val aeRegions = (physical[CameraCharacteristics.CONTROL_MAX_REGIONS_AE] ?: 0) > 0
        val logicalAfRegions = (logical[CameraCharacteristics.CONTROL_MAX_REGIONS_AF] ?: 0) > 0
        val logicalAeRegions = (logical[CameraCharacteristics.CONTROL_MAX_REGIONS_AE] ?: 0) > 0
        val exposureRange = logical[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE] ?: Range(0, 0)
        val exposureStep = logical[CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP] ?: Rational.ZERO
        val torch = logical[CameraCharacteristics.FLASH_INFO_AVAILABLE] == true
    }

    private data class Frame(
        val evidence: Evidence,
        val timestamp: Long,
        val physicalTimestamp: Long?,
        val crop: Rect
    ) {
        val earliestTimestamp get() = minOf(timestamp, physicalTimestamp ?: timestamp)
    }
    private data class FocusPoint(val x: Double, val y: Double, val fraction: Float) {
        fun region(array: Rect): MeteringRectangle {
            val width = (array.width() * fraction).roundToInt().coerceIn(1, array.width())
            val height = (array.height() * fraction).roundToInt().coerceIn(1, array.height())
            val left = (array.left + x * array.width() - width / 2.0).roundToInt()
                .coerceIn(array.left, array.right - width)
            val top = (array.top + y * array.height() - height / 2.0).roundToInt()
                .coerceIn(array.top, array.bottom - height)
            return MeteringRectangle(left, top, width, height, MeteringRectangle.METERING_WEIGHT_MAX)
        }
    }

    private class Shot(val file: File, val finished: (Result<Unit>) -> Unit) {
        var frame: Frame? = null
        var image: Image? = null
    }

    private val manager = context.getSystemService(CameraManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("HorizonCam-physical-tele").apply { start() }
    private val worker = Handler(thread.looper)
    private val closeListeners = mutableListOf<() -> Unit>()

    private var started = false
    private var closeRequested = false
    private var closed = false
    private var texture: SurfaceTexture? = null
    private var detachedTexture = false
    private var deliveredTimestamp = 0L
    private var previewFrame: Frame? = null
    private var viewToBuffer: PreviewFitTransform.Affine? = null
    private var mainCaps: Capabilities? = null
    var evidence: Evidence? = null
        private set
    var captureInFlight = false
        private set
    val nativeEdge: Int get() = JPEG_SIZE.width
    val exposureRange: Range<Int> get() = mainCaps?.exposureRange ?: Range(0, 0)
    val exposureStep: Rational get() = mainCaps?.exposureStep ?: Rational.ZERO
    val hasTorch: Boolean get() = mainCaps?.torch == true
    val supportsFocus: Boolean get() = mainCaps?.let { it.autofocus || it.logicalAeRegions || it.aeRegions } == true
    val supportsDistantFocus: Boolean get() = mainCaps?.manualFocus == true
    var exposureIndex: Int = 0
        private set
    var torchEnabled: Boolean = false
        private set

    private lateinit var caps: Capabilities
    private var device: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var opening = false
    private var closing = false
    private var closingDevice = false
    private var released = false
    private var terminalError: Throwable? = null
    private var lastFrame: Frame? = null
    private var shot: Shot? = null
    private var compensation = 0
    private var useTorch = false
    private var distant = false
    private var focus: FocusPoint? = null

    private val openDeadline = Runnable { fail(IllegalStateException("A teleobjetiva não entregou prévia confirmada.")) }
    private val shotDeadline = Runnable {
        shot?.let { finishShot(it, Result.failure(IllegalStateException("A teleobjetiva não entregou o JPEG."))) }
    }
    private val resetFocus = Runnable {
        focus = null
        if (!closing) runRequest { repeatPreview() }
    }

    fun start() {
        if (started || closeRequested || closed) return
        started = true
        preview.surfaceTextureListener = this
        worker.postDelayed(openDeadline, 4_000L)
        if (preview.isAvailable) preview.surfaceTexture?.let(::attach)
    }

    fun close(onClosed: () -> Unit = {}) {
        if (closed) {
            onClosed()
            return
        }
        closeListeners += onClosed
        if (closeRequested) return
        closeRequested = true
        evidence = null
        worker.post(::closeCamera)
    }

    fun captureTo(file: File, finished: (Result<Unit>) -> Unit) {
        if (closeRequested || closed || evidence == null || captureInFlight) {
            main.post { finished(Result.failure(IllegalStateException("A teleobjetiva ainda não está pronta."))) }
            return
        }
        captureInFlight = true
        fun complete(result: Result<Unit>) {
            captureInFlight = false
            try { finished(result) } finally { onCaptureIdle() }
        }
        if (!worker.post { requestJpeg(file, ::complete) }) {
            main.post { complete(Result.failure(CancellationException("Sessão da teleobjetiva encerrada."))) }
        }
    }

    fun focusAt(x: Float, y: Float, residual: Float = 1f): Boolean {
        if (closeRequested || closed || evidence == null || !supportsFocus) return false
        val (bx, by) = viewToBuffer?.map(x.toDouble(), y.toDouble()) ?: return false
        if (bx !in 0.0..PREVIEW_SIZE.width.toDouble() || by !in 0.0..PREVIEW_SIZE.height.toDouble()) return false
        val point = FocusPoint(bx / PREVIEW_SIZE.width, by / PREVIEW_SIZE.height,
            (0.08f / residual.coerceAtLeast(1f)).coerceAtLeast(0.005f))
        return worker.post {
            if (closing || cameraSession == null) return@post
            distant = false
            focus = point
            runRequest {
                repeatPreview()
                if (caps.autofocus) {
                    val request = request(CameraDevice.TEMPLATE_PREVIEW, checkNotNull(previewSurface))
                        .apply { set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START) }
                        .build()
                    checkNotNull(cameraSession).capture(request, previewCallback, worker)
                }
            }
            worker.removeCallbacks(resetFocus)
            worker.postDelayed(resetFocus, 8_000L)
        }
    }

    fun setExposureCompensation(index: Int) {
        val next = exposureRange.clamp(index)
        if (closeRequested || closed || next == exposureIndex) return
        exposureIndex = next
        worker.post {
            compensation = next
            if (!closing && cameraSession != null) runRequest { repeatPreview() }
        }
    }

    fun setTorch(enabled: Boolean) {
        if (closeRequested || closed || !hasTorch || enabled == torchEnabled) return
        torchEnabled = enabled
        worker.post {
            useTorch = enabled
            if (!closing && cameraSession != null) runRequest { repeatPreview() }
        }
    }

    fun setDistantFocus(enabled: Boolean): Boolean {
        if (closeRequested || closed || !supportsDistantFocus) return false
        return worker.post {
            if (closing) return@post
            distant = enabled
            focus = null
            worker.removeCallbacks(resetFocus)
            if (cameraSession != null) runRequest { repeatPreview() }
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        attach(surface)
        updateTransform()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (surface === texture) updateTransform()
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (surface !== texture || closeRequested || detachedTexture || closed || evidence != null) return
        deliveredTimestamp = surface.timestamp
        publishReady()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (closed || surface !== texture) return true
        detachedTexture = true
        if (!closeRequested) worker.post { fail(IllegalStateException("A superfície da teleobjetiva foi removida.")) }
        // TextureView relinquishes ownership here. Release only after CameraDevice.onClosed.
        return false
    }

    private fun attach(surface: SurfaceTexture) {
        if (closeRequested || closed || texture != null) return
        texture = surface
        surface.setDefaultBufferSize(PREVIEW_SIZE.width, PREVIEW_SIZE.height)
        worker.post { open(surface) }
    }

    private fun updateTransform() {
        val config = mainCaps ?: return
        val width = preview.width
        val height = preview.height
        if (width <= 0 || height <= 0 || closeRequested || closed || detachedTexture) return
        val fit = PreviewFitTransform.calculate(PREVIEW_SIZE.width, PREVIEW_SIZE.height,
            PreviewFitTransform.Crop(0.0, 0.0, PREVIEW_SIZE.width.toDouble(), PREVIEW_SIZE.height.toDouble()),
            relativeRotation(config), width, height, config.surfaceOrientation)
        preview.setTransform(Matrix().apply { setValues(fit.textureViewTransform.values()) })
        preview.clipBounds = Rect(fit.visible.left.roundToInt(), fit.visible.top.roundToInt(),
            fit.visible.right.roundToInt(), fit.visible.bottom.roundToInt())
        viewToBuffer = fit.bufferToView.inverse()
        publishReady()
    }

    private fun publishReady() {
        val frame = previewFrame ?: return
        if (evidence != null || closeRequested || closed || detachedTexture || viewToBuffer == null ||
            deliveredTimestamp < frame.earliestTimestamp) return
        evidence = frame.evidence
        worker.removeCallbacks(openDeadline)
        onReady(frame.evidence)
    }

    @SuppressLint("MissingPermission")
    private fun open(surface: SurfaceTexture) {
        if (closing) return
        runRequest {
            val logical = manager.getCameraCharacteristics(LOGICAL_ID)
            require(PHYSICAL_ID in logical.physicalCameraIds) { "A câmera 0 não lista a teleobjetiva física 6." }
            val physical = manager.getCameraCharacteristics(PHYSICAL_ID)
            val streams = checkNotNull(physical[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP])
            require(PREVIEW_SIZE in streams.getOutputSizes(SurfaceTexture::class.java).orEmpty() &&
                JPEG_SIZE in streams.getOutputSizes(ImageFormat.JPEG).orEmpty()) {
                "A câmera física 6 não lista a prévia ou o JPEG 4:3 necessários."
            }
            caps = Capabilities(logical, physical)
            val config = caps
            main.post {
                if (!closeRequested && !closed) {
                    mainCaps = config
                    updateTransform()
                }
            }
            previewSurface = Surface(surface)
            jpegReader = ImageReader.newInstance(JPEG_SIZE.width, JPEG_SIZE.height, ImageFormat.JPEG, 2)
            jpegReader?.setOnImageAvailableListener(::readJpeg, worker)
            opening = true
            try {
                manager.openCamera(LOGICAL_ID, deviceCallback, worker)
            } catch (error: Exception) {
                opening = false
                throw error
            }
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            opening = false
            device = camera
            if (closing) closeCamera() else runRequest { configure(camera) }
        }

        override fun onDisconnected(camera: CameraDevice) = onError(camera, ERROR_CAMERA_DEVICE)

        override fun onError(camera: CameraDevice, error: Int) {
            opening = false
            device = camera
            fail(IllegalStateException("Erro Camera2 na teleobjetiva: $error."))
        }

        override fun onClosed(camera: CameraDevice) {
            if (device !== camera) return
            device = null
            if (!closing) terminalError = IllegalStateException("A câmera fechou durante a sessão da teleobjetiva.")
            closeCamera()
        }
    }

    private fun configure(camera: CameraDevice) {
        val outputs = listOf(checkNotNull(previewSurface), checkNotNull(jpegReader).surface).map { surface ->
            OutputConfiguration(surface).apply {
                setPhysicalCameraId(PHYSICAL_ID)
                if (Build.VERSION.SDK_INT >= 33) timestampBase = OutputConfiguration.TIMESTAMP_BASE_SENSOR
            }
        }
        camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs,
            { worker.post(it) }, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (closing || device !== camera) return
                    cameraSession = session
                    runRequest { repeatPreview() }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (!closing && device === camera) fail(IllegalStateException("Sessão física 0/6 recusada."))
                }
            }))
    }

    private fun repeatPreview() {
        val session = checkNotNull(cameraSession)
        val target = checkNotNull(previewSurface)
        session.setRepeatingRequest(request(CameraDevice.TEMPLATE_PREVIEW, target).build(), previewCallback, worker)
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (closing || session !== cameraSession) return
            val frame = readFrame(result) ?: return
            val previous = lastFrame
            lastFrame = frame
            if (previous == null || abs(previous.evidence.cropZoom - frame.evidence.cropZoom) > 0.001f) {
                main.post {
                    if (closeRequested || closed || detachedTexture) return@post
                    previewFrame = frame
                    publishReady()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun readFrame(result: TotalCaptureResult): Frame? {
        val physical = if (Build.VERSION.SDK_INT >= 31) {
            result.physicalCameraTotalResults[PHYSICAL_ID] ?: result.physicalCameraResults[PHYSICAL_ID]
        } else result.physicalCameraResults[PHYSICAL_ID]
        physical ?: return null
        val focal = physical[CaptureResult.LENS_FOCAL_LENGTH] ?: return null
        if (!focal.isFinite() || abs(focal - EXPECTED_FOCAL_MM) > 0.05f) return null
        val timestamp = result[CaptureResult.SENSOR_TIMESTAMP]?.takeIf { it > 0L } ?: return null
        val active = if (Build.VERSION.SDK_INT >= 28 &&
            physical[CaptureResult.DISTORTION_CORRECTION_MODE] == CaptureResult.DISTORTION_CORRECTION_MODE_OFF) {
            caps.physical[CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE] ?: caps.sensorArray
        } else caps.sensorArray
        val crop = physical[CaptureResult.SCALER_CROP_REGION]?.takeIf { !it.isEmpty && active.contains(it) } ?: active
        val zoom = minOf(active.width().toFloat() / crop.width(), active.height().toFloat() / crop.height())
        return Frame(Evidence(PHYSICAL_ID, focal, zoom), timestamp,
            physical[CaptureResult.SENSOR_TIMESTAMP]?.takeIf { it > 0L }, Rect(crop))
    }

    private fun request(template: Int, target: Surface): CaptureRequest.Builder {
        val camera = checkNotNull(device)
        val usePhysicalKeys = caps.physicalKeys.isNotEmpty() && (focus != null || distant || compensation != 0)
        val builder = if (usePhysicalKeys) camera.createCaptureRequest(template, setOf(PHYSICAL_ID))
            else camera.createCaptureRequest(template)
        fun <T> set(key: CaptureRequest.Key<T>, value: T) {
            if (key in caps.requestKeys) builder.set(key, value)
            if (usePhysicalKeys && key in caps.physicalKeys) builder.setPhysicalCameraKey(key, value, PHYSICAL_ID)
        }
        builder.addTarget(target)
        if (Build.VERSION.SDK_INT >= 31 &&
            CaptureRequest.SCALER_ROTATE_AND_CROP_NONE in
                (caps.logical[CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES] ?: intArrayOf())) {
            set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
        }
        val autofocusMode = when {
            distant -> CaptureRequest.CONTROL_AF_MODE_OFF
            focus != null && caps.autofocus -> CaptureRequest.CONTROL_AF_MODE_AUTO
            caps.continuousFocus -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            caps.autofocus -> CaptureRequest.CONTROL_AF_MODE_AUTO
            else -> CaptureRequest.CONTROL_AF_MODE_OFF
        }
        set(CaptureRequest.CONTROL_AF_MODE, autofocusMode)
        if (distant) set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
        set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation)
        if (caps.torch) set(CaptureRequest.FLASH_MODE,
            if (useTorch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        focus?.let { point ->
            val physicalCrop = lastFrame?.crop ?: caps.sensorArray
            if (caps.logicalAfRegions) builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(point.region(caps.logicalArray)))
            if (caps.logicalAeRegions) builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(point.region(caps.logicalArray)))
            if (caps.afRegions && CaptureRequest.CONTROL_AF_REGIONS in caps.physicalKeys) {
                builder.setPhysicalCameraKey(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(point.region(physicalCrop)), PHYSICAL_ID)
            }
            if (caps.aeRegions && CaptureRequest.CONTROL_AE_REGIONS in caps.physicalKeys) {
                builder.setPhysicalCameraKey(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(point.region(physicalCrop)), PHYSICAL_ID)
            }
        }
        return builder
    }

    private fun relativeRotation(config: Capabilities): Int {
        val displayDegrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (config.sensorOrientation - displayDegrees + 360) % 360
    }

    private fun requestJpeg(file: File, finished: (Result<Unit>) -> Unit) {
        val current = cameraSession
        val reader = jpegReader
        val target = previewSurface
        val expected = lastFrame
        if (closing || current == null || reader == null || target == null || shot != null || expected == null) {
            main.post { finished(Result.failure(IllegalStateException("Captura da teleobjetiva indisponível."))) }
            return
        }
        val pending = Shot(file, finished)
        shot = pending
        worker.postDelayed(shotDeadline, 3_000L)
        try {
            val request = request(CameraDevice.TEMPLATE_STILL_CAPTURE, reader.surface).apply {
                addTarget(target)
                set(CaptureRequest.JPEG_ORIENTATION, relativeRotation(caps))
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            }.build()
            current.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (shot !== pending || closing) return
                    val actual = readFrame(result)
                    if (actual == null || abs(actual.evidence.cropZoom - expected.evidence.cropZoom) >
                        expected.evidence.cropZoom * 0.02f) {
                        finishShot(pending, Result.failure(IllegalStateException("O JPEG não confirmou o enquadramento da teleobjetiva.")))
                        return
                    }
                    pending.frame = actual
                    completeJpeg(pending)
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    finishShot(pending, Result.failure(IllegalStateException("Captura Camera2 falhou: ${failure.reason}.")))
                }

                override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                    finishShot(pending, Result.failure(CancellationException("Captura da teleobjetiva interrompida.")))
                }

                override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest,
                                                 target: Surface, frameNumber: Long) {
                    if (target === reader.surface) {
                        finishShot(pending, Result.failure(IllegalStateException("A saída JPEG da teleobjetiva perdeu o quadro.")))
                    }
                }
            }, worker)
        } catch (error: Exception) {
            finishShot(pending, Result.failure(error))
        }
    }

    private fun readJpeg(reader: ImageReader) {
        val image = try { reader.acquireNextImage() } catch (_: IllegalStateException) { null } ?: return
        val pending = shot
        if (pending == null || closing) {
            image.close()
            return
        }
        pending.image?.close()
        pending.image = image
        completeJpeg(pending)
    }

    private fun completeJpeg(pending: Shot) {
        val image = pending.image ?: return
        val frame = pending.frame ?: return
        // The JPEG is pinned to 6 and on some HALs its buffer timestamp comes
        // from the physical result. Accept only the two timestamps of this shot.
        if (image.timestamp != frame.timestamp && image.timestamp != frame.physicalTimestamp) {
            image.close()
            pending.image = null
            return
        }
        val result = runCatching {
            val data = image.planes[0].buffer
            FileOutputStream(pending.file).channel.use { channel ->
                while (data.hasRemaining()) channel.write(data)
            }
        }
        finishShot(pending, result)
    }

    private fun finishShot(pending: Shot, result: Result<Unit>) {
        if (shot !== pending) return
        shot = null
        worker.removeCallbacks(shotDeadline)
        pending.image?.close()
        main.post { pending.finished(result) }
    }

    private fun runRequest(operation: () -> Unit) {
        try { operation() } catch (error: CameraAccessException) { fail(error) }
        catch (error: IllegalArgumentException) { fail(error) }
        catch (error: IllegalStateException) { fail(error) }
        catch (error: SecurityException) { fail(error) }
    }

    private fun fail(error: Throwable) {
        if (!closing) terminalError = error
        closeCamera()
    }

    private fun closeCamera() {
        closing = true
        worker.removeCallbacks(openDeadline)
        worker.removeCallbacks(resetFocus)
        shot?.let { finishShot(it, Result.failure(CancellationException("Sessão da teleobjetiva encerrada."))) }
        val camera = device
        if (camera != null) {
            if (!closingDevice) {
                closingDevice = true
                // Keep the capture session and both outputs attached until onClosed.
                // Closing the session first makes this HAL reconfigure an empty graph.
                camera.close()
            }
        } else if (!opening && !released) {
            released = true
            cameraSession = null
            jpegReader?.close()
            jpegReader = null
            previewSurface?.release()
            previewSurface = null
            val error = terminalError
            main.post { finishClose(error) }
            thread.quitSafely()
        }
    }

    private fun finishClose(error: Throwable?) {
        closed = true
        evidence = null
        if (preview.surfaceTextureListener === this) preview.surfaceTextureListener = null
        if (detachedTexture) texture?.release()
        texture = null
        val listeners = closeListeners.toList()
        closeListeners.clear()
        if (!closeRequested && error != null) onError(error)
        listeners.forEach { it() }
    }

    private companion object {
        const val LOGICAL_ID = "0"
        const val PHYSICAL_ID = "6"
        const val EXPECTED_FOCAL_MM = 7f
        val PREVIEW_SIZE = Size(1440, 1080)
        val JPEG_SIZE = Size(3648, 2736)
    }
}
