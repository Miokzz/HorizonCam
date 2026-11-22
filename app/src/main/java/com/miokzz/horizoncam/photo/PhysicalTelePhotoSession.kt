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
import android.os.SystemClock
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
 * Direct Camera2 owner for the S24's fixed 0/6 and persistent logical JPEG routes.
 * The view, public controls, and callbacks belong to main. Camera operations and
 * JPEG I/O use one serial handler.
 */
internal class PhysicalTelePhotoSession(
    context: Context,
    private val preview: TextureView,
    private val rotation: Int,
    private val onReady: (Evidence) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onCaptureIdle: () -> Unit = {},
    logicalInitialZoom: Float? = null,
    private val onLogicalZoomApplied: (Evidence) -> Unit = {}
) : TextureView.SurfaceTextureListener {
    data class Evidence(
        val physicalCameraId: String,
        val focalLengthMm: Float,
        val cropZoom: Float,
        val logicalZoom: Float? = null
    )

    private data class ZoomRequest(val generation: Long, val ratio: Float) {
        val physicalId: String get() = when {
            ratio < 1f -> "2"
            ratio < 3f -> "5"
            else -> PHYSICAL_ID
        }

        fun matches(actualRatio: Float, actualId: String, focal: Float): Boolean =
            abs(actualRatio - ratio) <= ratio * 0.005f && actualId == physicalId &&
                abs(focal - checkNotNull(LOGICAL_FOCALS[actualId])) <= 0.05f
    }

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
        val crop: Rect,
        val zoomRequest: ZoomRequest? = null
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

    val isLogicalZoomSession = logicalInitialZoom != null
    private val jpegSize = if (isLogicalZoomSession) LOGICAL_JPEG_SIZE else PHYSICAL_JPEG_SIZE
    @Volatile private var requestedLogicalZoom = logicalInitialZoom?.let { ZoomRequest(0, nativeRatio(it)) }
    @Volatile private var deliveredLogicalZoom: ZoomRequest? = null

    private val manager = context.getSystemService(CameraManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread(if (isLogicalZoomSession) "HorizonCam-logical-photo" else "HorizonCam-physical-tele")
        .apply { start() }
    private val worker = Handler(thread.looper)
    private val closeListeners = mutableListOf<() -> Unit>()

    private var started = false
    private var closeRequested = false
    private var closed = false
    private var texture: SurfaceTexture? = null
    private var detachedTexture = false
    private var deliveredTimestamp = 0L
    private var previewFrame: Frame? = null
    private val logicalPreviewFrames = LinkedHashMap<Long, Frame>()
    private var deliveredLogicalFrame: Frame? = null
    private var viewToBuffer: PreviewFitTransform.Affine? = null
    private var mainCaps: Capabilities? = null
    var evidence: Evidence? = null
        private set
    var captureInFlight = false
        private set
    val nativeEdge: Int get() = jpegSize.width
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
    private var submittedLogicalZoom: ZoomRequest? = null
    private var logicalMismatchStartedAt = 0L
    private var logicalMismatch: String? = null
    private var shot: Shot? = null
    private var compensation = 0
    private var useTorch = false
    private var distant = false
    private var focus: FocusPoint? = null

    private val openDeadline = Runnable { fail(IllegalStateException("A câmera não entregou prévia confirmada.")) }
    private val logicalZoomDeadline = Runnable {
        val requested = requestedLogicalZoom
        if (requested != null && requested == submittedLogicalZoom && requested != deliveredLogicalZoom) {
            val detail = logicalMismatch ?: "sem correspondência entre resultado e textura"
            fail(IllegalStateException("A sessão lógica não confirmou ${requested.ratio}× no sensor ${requested.physicalId} ($detail)."))
        }
    }
    private val logicalZoomUpdate = Runnable { applyLatestLogicalZoom() }
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

    fun setLogicalZoom(ratio: Float) {
        val previous = requestedLogicalZoom ?: return
        if (closeRequested || closed) return
        val zoom = nativeRatio(ratio)
        if (previous.ratio == zoom) return
        requestedLogicalZoom = ZoomRequest(previous.generation + 1L, zoom)
        logicalPreviewFrames.clear()
        worker.removeCallbacks(logicalZoomUpdate)
        worker.post(logicalZoomUpdate)
    }

    fun hasConfirmedLogicalZoom(ratio: Float): Boolean {
        val requested = requestedLogicalZoom ?: return false
        return !closeRequested && !closed && ratio.isFinite() &&
            requested.ratio == nativeRatio(ratio) && requested == deliveredLogicalZoom
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
        val expected = deliveredLogicalFrame
        if (closeRequested || closed || evidence == null || captureInFlight ||
            (isLogicalZoomSession && (expected == null || expected.zoomRequest != requestedLogicalZoom))) {
            main.post { finished(Result.failure(IllegalStateException("A câmera ainda não está pronta."))) }
            return
        }
        captureInFlight = true
        fun complete(result: Result<Unit>) {
            captureInFlight = false
            try { finished(result) } finally { onCaptureIdle() }
        }
        if (!worker.post { requestJpeg(file, expected, ::complete) }) {
            main.post { complete(Result.failure(CancellationException("Sessão da teleobjetiva encerrada."))) }
        }
    }

    fun focusAt(x: Float, y: Float, residual: Float = 1f): Boolean {
        if (closeRequested || closed || evidence == null || !supportsFocus ||
            requestedLogicalZoom != deliveredLogicalZoom) return false
        val (bx, by) = viewToBuffer?.map(x.toDouble(), y.toDouble()) ?: return false
        if (bx !in 0.0..PREVIEW_SIZE.width.toDouble() || by !in 0.0..PREVIEW_SIZE.height.toDouble()) return false
        val point = FocusPoint(bx / PREVIEW_SIZE.width, by / PREVIEW_SIZE.height,
            (0.08f / residual.coerceAtLeast(1f)).coerceAtLeast(0.005f))
        val focusedZoom = deliveredLogicalZoom
        return worker.post {
            if (closing || cameraSession == null || focusedZoom != requestedLogicalZoom) return@post
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
        if (surface !== texture || closeRequested || detachedTexture || closed) return
        if (evidence != null && requestedLogicalZoom == deliveredLogicalZoom) return
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
        if (closeRequested || closed || detachedTexture || viewToBuffer == null) return
        val firstFrame = evidence == null
        val zoom = requestedLogicalZoom
        val frame = if (zoom != null) {
            if (zoom == deliveredLogicalZoom) return
            // Both the capture result and the displayed texture must belong to this request.
            logicalPreviewFrames[deliveredTimestamp]?.takeIf { it.zoomRequest == zoom } ?: return
        } else {
            if (!firstFrame) return
            previewFrame?.takeIf { deliveredTimestamp >= it.earliestTimestamp } ?: return
        }
        evidence = frame.evidence
        if (zoom != null) {
            deliveredLogicalFrame = frame
            deliveredLogicalZoom = zoom
            logicalPreviewFrames.clear()
        }
        if (firstFrame) {
            worker.removeCallbacks(openDeadline)
            onReady(frame.evidence)
        } else onLogicalZoomApplied(frame.evidence)
    }

    @SuppressLint("MissingPermission")
    private fun open(surface: SurfaceTexture) {
        if (closing) return
        runRequest {
            val logical = manager.getCameraCharacteristics(LOGICAL_ID)
            val source = if (isLogicalZoomSession) {
                if (Build.VERSION.SDK_INT < 33) {
                    error("A sessão lógica precisa de timestamps de sensor na textura.")
                }
                require(logical.physicalCameraIds.containsAll(LOGICAL_FOCALS.keys)) {
                    "A câmera lógica não anuncia os três sensores."
                }
                val range = logical[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE]
                require(range != null && MIN_LOGICAL_ZOOM in range && MAX_LOGICAL_ZOOM in range &&
                    CaptureRequest.CONTROL_ZOOM_RATIO in logical.availableCaptureRequestKeys) {
                    "A faixa de zoom lógico necessária não está disponível."
                }
                logical
            } else {
                require(PHYSICAL_ID in logical.physicalCameraIds) { "A câmera 0 não lista a teleobjetiva física 6." }
                manager.getCameraCharacteristics(PHYSICAL_ID)
            }
            val streams = checkNotNull(source[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP])
            require(PREVIEW_SIZE in streams.getOutputSizes(SurfaceTexture::class.java).orEmpty() &&
                jpegSize in streams.getOutputSizes(ImageFormat.JPEG).orEmpty()) {
                "A câmera não lista a combinação de prévia e JPEG 4:3 necessária."
            }
            caps = Capabilities(logical, source)
            val config = caps
            main.post {
                if (!closeRequested && !closed) {
                    mainCaps = config
                    updateTransform()
                }
            }
            previewSurface = Surface(surface)
            jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
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
                if (!isLogicalZoomSession) setPhysicalCameraId(PHYSICAL_ID)
                if (Build.VERSION.SDK_INT >= 33) timestampBase = OutputConfiguration.TIMESTAMP_BASE_SENSOR
            }
        }
        val configuration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs,
            { worker.post(it) }, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (closing || device !== camera) return
                    cameraSession = session
                    runRequest { repeatPreview() }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (!closing && device === camera) fail(IllegalStateException("Configuração Camera2 recusada."))
                }
            })
        if (isLogicalZoomSession) {
            try {
                check(camera.isSessionConfigurationSupported(configuration)) { "A combinação lógica de prévia e JPEG foi recusada." }
            } catch (_: UnsupportedOperationException) {
                // Some HALs only report support when the session is opened.
            }
        }
        camera.createCaptureSession(configuration)
    }

    private fun applyLatestLogicalZoom() {
        if (!closing && cameraSession != null && shot == null &&
            requestedLogicalZoom != submittedLogicalZoom) runRequest { repeatPreview() }
    }

    private fun repeatPreview() {
        val session = checkNotNull(cameraSession)
        val target = checkNotNull(previewSurface)
        val zoom = if (shot == null) requestedLogicalZoom else submittedLogicalZoom
        val previousZoom = submittedLogicalZoom
        if (zoom != null && zoom != previousZoom) {
            if (previousZoom != null && previousZoom.physicalId != zoom.physicalId) {
                focus = null
                worker.removeCallbacks(resetFocus)
            }
            lastFrame = null
            logicalMismatch = null
            logicalMismatchStartedAt = 0L
        }
        val previewRequest = request(CameraDevice.TEMPLATE_PREVIEW, target, zoom).build()
        session.setRepeatingRequest(previewRequest, previewCallback, worker)
        if (zoom != null && zoom != previousZoom) {
            submittedLogicalZoom = zoom
            worker.removeCallbacks(logicalZoomDeadline)
            worker.postDelayed(logicalZoomDeadline, LOGICAL_ZOOM_TIMEOUT_MS)
        }
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (closing || session !== cameraSession) return
            if (isLogicalZoomSession) {
                readLogicalPreview(request, result)
                return
            }
            val frame = readPhysicalFrame(result) ?: return
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

    private fun readLogicalPreview(request: CaptureRequest, result: TotalCaptureResult) {
        val zoom = request.tag as? ZoomRequest ?: return
        if (zoom != requestedLogicalZoom) return
        val frame = readLogicalFrame(result, zoom)
        lastFrame = frame
        if (frame == null) {
            logicalMismatch = "sensor=${result[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID]} " +
                "focal=${result[CaptureResult.LENS_FOCAL_LENGTH]}"
            if (deliveredLogicalZoom == zoom) {
                val now = SystemClock.elapsedRealtime()
                if (logicalMismatchStartedAt == 0L) logicalMismatchStartedAt = now
                else if (now - logicalMismatchStartedAt >= LOGICAL_ZOOM_TIMEOUT_MS && zoom == requestedLogicalZoom) {
                    fail(IllegalStateException("A sessão lógica perdeu o sensor confirmado para ${zoom.ratio}× ($logicalMismatch)."))
                }
            }
            return
        }
        logicalMismatch = null
        logicalMismatchStartedAt = 0L
        if (zoom == deliveredLogicalZoom) return
        main.post {
            if (closeRequested || closed || detachedTexture || zoom != requestedLogicalZoom ||
                zoom == deliveredLogicalZoom) return@post
            logicalPreviewFrames[frame.timestamp] = frame
            if (logicalPreviewFrames.size > MAX_PREVIEW_METADATA) {
                logicalPreviewFrames.remove(logicalPreviewFrames.keys.first())
            }
            publishReady()
        }
    }

    private fun readLogicalFrame(result: TotalCaptureResult, zoom: ZoomRequest): Frame? {
        if (Build.VERSION.SDK_INT < 30) return null
        val id = result[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID] ?: return null
        val focal = result[CaptureResult.LENS_FOCAL_LENGTH] ?: return null
        val ratio = result[CaptureResult.CONTROL_ZOOM_RATIO] ?: return null
        if (!zoom.matches(ratio, id, focal)) return null
        val timestamp = result[CaptureResult.SENSOR_TIMESTAMP]?.takeIf { it > 0L } ?: return null
        val active = if (result[CaptureResult.DISTORTION_CORRECTION_MODE] == CaptureResult.DISTORTION_CORRECTION_MODE_OFF) {
            caps.logical[CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE] ?: caps.logicalArray
        } else caps.logicalArray
        val crop = result[CaptureResult.SCALER_CROP_REGION]?.takeIf { !it.isEmpty && active.contains(it) } ?: return null
        val residual = minOf(active.width().toFloat() / crop.width(), active.height().toFloat() / crop.height())
        return Frame(Evidence(id, focal, residual, ratio), timestamp, null, Rect(crop), zoom)
    }

    @Suppress("DEPRECATION")
    private fun readPhysicalFrame(result: TotalCaptureResult): Frame? {
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

    private fun request(template: Int, target: Surface, zoom: ZoomRequest? = submittedLogicalZoom): CaptureRequest.Builder {
        val camera = checkNotNull(device)
        val usePhysicalKeys = !isLogicalZoomSession && caps.physicalKeys.isNotEmpty() &&
            (focus != null || distant || compensation != 0)
        val builder = if (usePhysicalKeys) camera.createCaptureRequest(template, setOf(PHYSICAL_ID))
            else camera.createCaptureRequest(template)
        fun <T> set(key: CaptureRequest.Key<T>, value: T) {
            if (key in caps.requestKeys) builder.set(key, value)
            if (usePhysicalKeys && key in caps.physicalKeys) builder.setPhysicalCameraKey(key, value, PHYSICAL_ID)
        }
        builder.addTarget(target)
        if (zoom != null && Build.VERSION.SDK_INT >= 30) {
            builder.setTag(zoom)
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom.ratio)
        }
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
            val logicalCrop = if (isLogicalZoomSession) lastFrame?.crop ?: caps.logicalArray else caps.logicalArray
            if (caps.logicalAfRegions) builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(point.region(logicalCrop)))
            if (caps.logicalAeRegions) builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(point.region(logicalCrop)))
            if (usePhysicalKeys && caps.afRegions && CaptureRequest.CONTROL_AF_REGIONS in caps.physicalKeys) {
                builder.setPhysicalCameraKey(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(point.region(physicalCrop)), PHYSICAL_ID)
            }
            if (usePhysicalKeys && caps.aeRegions && CaptureRequest.CONTROL_AE_REGIONS in caps.physicalKeys) {
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

    private fun requestJpeg(file: File, confirmedLogicalFrame: Frame?, finished: (Result<Unit>) -> Unit) {
        val current = cameraSession
        val reader = jpegReader
        val target = previewSurface
        val expected = confirmedLogicalFrame ?: lastFrame
        val zoom = confirmedLogicalFrame?.zoomRequest
        if (closing || current == null || reader == null || target == null || shot != null || expected == null ||
            (isLogicalZoomSession && (zoom == null || zoom != lastFrame?.zoomRequest))) {
            main.post { finished(Result.failure(IllegalStateException("Captura Camera2 indisponível."))) }
            return
        }
        val pending = Shot(file, finished)
        shot = pending
        worker.postDelayed(shotDeadline, 3_000L)
        try {
            val request = request(CameraDevice.TEMPLATE_STILL_CAPTURE, reader.surface, zoom).apply {
                addTarget(target)
                set(CaptureRequest.JPEG_ORIENTATION, relativeRotation(caps))
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            }.build()
            current.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (shot !== pending || closing) return
                    val actual = if (zoom != null) readLogicalFrame(result, zoom) else readPhysicalFrame(result)
                    if (actual == null || (zoom != null && actual.crop != expected.crop) ||
                        abs(actual.evidence.cropZoom - expected.evidence.cropZoom) >
                        expected.evidence.cropZoom * 0.02f) {
                        val error = IllegalStateException("O JPEG não confirmou o enquadramento da prévia.")
                        finishShot(pending, Result.failure(error))
                        if (zoom != null) fail(error)
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
        // A pinned JPEG can use the physical timestamp. The logical stream
        // uses the logical result timestamp already verified for this shot.
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
        applyLatestLogicalZoom()
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
        worker.removeCallbacks(logicalZoomDeadline)
        worker.removeCallbacks(logicalZoomUpdate)
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
        deliveredLogicalFrame = null
        logicalPreviewFrames.clear()
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
        const val MIN_LOGICAL_ZOOM = 0.6f
        const val MAX_LOGICAL_ZOOM = 3f
        const val LOGICAL_ZOOM_TIMEOUT_MS = 1_500L
        const val MAX_PREVIEW_METADATA = 12
        val LOGICAL_FOCALS = mapOf("2" to 2.2f, "5" to 5.4f, PHYSICAL_ID to EXPECTED_FOCAL_MM)
        val PREVIEW_SIZE = Size(1440, 1080)
        val PHYSICAL_JPEG_SIZE = Size(3648, 2736)
        val LOGICAL_JPEG_SIZE = Size(4080, 3060)

        fun nativeRatio(ratio: Float): Float {
            require(ratio.isFinite()) { "Zoom lógico inválido." }
            return ratio.coerceIn(MIN_LOGICAL_ZOOM, MAX_LOGICAL_ZOOM)
        }
    }
}
