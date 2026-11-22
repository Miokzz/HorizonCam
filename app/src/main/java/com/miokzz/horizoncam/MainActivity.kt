package com.miokzz.horizoncam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.miokzz.horizoncam.camera.CameraCapabilities
import com.miokzz.horizoncam.camera.LensOption
import com.miokzz.horizoncam.camera.PhotoZoomDetents
import com.miokzz.horizoncam.camera.PhotoZoomScale
import com.miokzz.horizoncam.camera.PhotoZoomTransition
import com.miokzz.horizoncam.camera.RearLensManager
import com.miokzz.horizoncam.camera.StillPreviewGeometry
import com.miokzz.horizoncam.camera.StillFraming
import com.miokzz.horizoncam.camera.ZoomRoute
import com.miokzz.horizoncam.databinding.ActivityMainBinding
import com.miokzz.horizoncam.media.CaptureLibrary
import com.miokzz.horizoncam.media.RestorationJobs
import com.miokzz.horizoncam.media.BurstJobs
import com.miokzz.horizoncam.photo.BurstCapture
import com.miokzz.horizoncam.media.AutoPhotos
import com.miokzz.horizoncam.photo.AdvancedPhotoCapabilities
import com.miokzz.horizoncam.photo.AdvancedPhotoControls
import com.miokzz.horizoncam.photo.AdvancedPhotoSettings
import com.miokzz.horizoncam.photo.ManualPhotoSettings
import com.miokzz.horizoncam.photo.HdrPhotoExtension
import com.miokzz.horizoncam.ui.PhotoControlsDialog
import com.miokzz.horizoncam.photo.PhotoFormat
import com.miokzz.horizoncam.photo.PhotoEngine
import com.miokzz.horizoncam.video.VideoEngine
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class ShootingMode { PHOTO, VIDEO, PRO, NIGHT }

@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class MainActivity : AppCompatActivity(), DisplayManager.DisplayListener {
    private lateinit var ui: ActivityMainBinding
    private lateinit var library: CaptureLibrary
    private lateinit var sensor: GravityRollSensor
    private val horizon = HorizonState()
    private val prefs by lazy { getSharedPreferences("horizoncam", MODE_PRIVATE) }
    private val main by lazy { ContextCompat.getMainExecutor(this) }

    private var provider: ProcessCameraProvider? = null
    private var requestingProvider = false
    private var lenses: RearLensManager? = null
    private var lens: LensOption? = null
    private var camera: Camera? = null
    private var photo: PhotoEngine? = null
    private var video: VideoEngine? = null
    private var capabilities: CameraCapabilities? = null
    private val videoCapsCache = mutableMapOf<String, CameraCapabilities>()
    private val photoCapsCache = mutableMapOf<String, AdvancedPhotoCapabilities>()
    private val power by lazy { getSystemService(android.os.PowerManager::class.java) }
    private val thermalListener = android.os.PowerManager.OnThermalStatusChangedListener { level ->
        Log.i(TAG, "Nível térmico do sistema: $level")
        if (level >= android.os.PowerManager.THERMAL_STATUS_CRITICAL && recording != null && !stopping) {
            toast("Temperatura crítica: finalizando o MP4.")
            stopVideo()
        }
    }
    private var eisSupported = false
    private var attemptingNativeEis = false
    private var mode = ShootingMode.VIDEO
    private val photoMode get() = mode != ShootingMode.VIDEO
    private var photoSettings = AdvancedPhotoSettings()
    private var photoCaps: AdvancedPhotoCapabilities? = null
    private var hdrRequested = false
    private var hdr: HdrPhotoExtension? = null
    private val photoDialogs by lazy { PhotoControlsDialog(this) }
    private var requestedQuality = Quality.FHD
    private var fps = 30
    private var displayZoom = 1f
    private val photoZoom = PhotoZoomTransition()
    private var targetZoom: Float
        get() = photoZoom.intent.total
        set(value) { photoZoom.request(value, preferred = null, continuous = false, now = 0L) }
    private val softwareStillZoom get() = autoJpeg && !hdrRequested

    private fun usesS24LogicalMain(total: Float, preferred: String?) =
        softwareStillZoom && android.os.Build.MODEL == "SM-S9210" && preferred == null && total >= 1f && total < 3f
    private var cameraOpen = false
    private var previewStreaming = false
    private var bindingStartedAt = 0L
    private var firstFrameAt = 0L
    private var recoveryAttempt = 0
    private var recoveryMessage: String? = null
    private var reopen: Runnable? = null
    private var lensSwitch: Runnable? = null
    private var cancelPhotoClose: (() -> Unit)? = null
    private var recoveredPhotoGeneration: Long? = null
    private var handoffStartedAt: Long? = null
    private var handoffExposure: Float? = null
    private var photoSurfaceRequested = false
    private var draggingZoom = false
    private val zoomDetents = PhotoZoomDetents()
    private var sliderGeneration: Long? = null
    private var dragPreviewCrop: Float? = null
    private var inFlightNativeRatio: Float? = null
    private var appliedNativeRatio: Float? = null
    private var nextNativeRatio: Float? = null
    private var openingTimeout: Runnable? = null
    private var physicalVerification: Runnable? = null
    private var physicalConfirmed = false
    private var physicalCropZoom = 1f
    private var distantFocus = false
    private var lockLensConfigured = false
    private var lockLensExplicit = false
    private var lockReturnKey: String? = null
    private var lockReturnZoom = 1f
    private var focusMessage: String? = null
    private var lastFocusEvidence: String? = null
    private var focusCommand = 0
    private var extraCrop = 1f
    private var zoomCommand = 0
    private var zoomPending = false
    private val zoomInProgress get() = if (softwareStillZoom) photoZoom.isPending else zoomPending
    private val canZoom get() = (ready || handoffStartedAt != null) && failure == null
    private val autoJpeg get() = mode == ShootingMode.PHOTO && photoSettings.format == PhotoFormat.JPEG
    private var pinchZoom = 1f
    private var recording: Recording? = null
    private val manualOverrides = mutableSetOf<String>()
    private var applyingManual = false
    private var burst: BurstCapture? = null
    private var burstProgress = ""
    private var burstDetail = false
    private var burstZoom = 1f
    private var takingPhoto = false
    private var paused = false
    private var stopping = false
    private var awaitingMic = false
    private var pausedCommand = false
    private var ready = false
    private var audio = true
    private var failure: String? = null
    private var boundRotation = -1
    private var session = 0
    private var physicalLog: String? = null
    private val locked get() = !photoMode && horizon.isEnabled()
    private val lensProfile get() = if (photoMode) "$mode/${photoSettings.format}/${photoSettings.highResolution}/$hdrRequested"
        else "$mode/${horizon.mode()}/$requestedQuality/$fps"
    private val busy get() = recording != null || takingPhoto || awaitingMic || applyingManual
    private val presets get() = listOf(0.6f to ui.zoom06Button, 1f to ui.zoom1Button,
        2f to ui.zoom2Button, 3f to ui.zoom3Button)

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) openCamera() else {
            failure = "Permita o acesso à câmera"
            render()
        }
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        awaitingMic = false
        if (!granted) {
            audio = false
            prefs.edit().putBoolean("sound", false).apply()
            toast("Microfone desativado. O vídeo será salvo sem áudio.")
        }
        if (ready && !photoMode && !busy) beginVideo() else render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ViewCompat.setOnApplyWindowInsetsListener(ui.root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        library = CaptureLibrary(this)
        sensor = GravityRollSensor(this) { reading -> horizon.pushRoll(reading.timestampNs, reading.rollDegrees.toFloat()) }
        val previous = if (prefs.getBoolean("photoMode", true)) ShootingMode.PHOTO else ShootingMode.VIDEO
        mode = runCatching { ShootingMode.valueOf(prefs.getString("shootingMode", previous.name)!!) }.getOrDefault(previous)
        audio = prefs.getBoolean("sound", true)
        if (!sensor.isAvailable) horizon.setMode(StabilizationMode.OFF)
        if (prefs.contains("stabilization")) {
            runCatching { StabilizationMode.valueOf(prefs.getString("stabilization", "")!!) }
                .getOrNull()?.takeIf { it != StabilizationMode.HORIZONTAL_LOCK || sensor.isAvailable }
                ?.let(horizon::setMode)
        }
        ui.gridView.isVisible = prefs.getBoolean("grid", false)
        library.latest?.let { library.inspectAndLoad(it, ui.galleryThumbnail::setImageBitmap) }
        ui.galleryThumbnail.setOnClickListener {
            library.latest?.let { uri ->
                runCatching { startActivity(library.viewIntent(uri)) }.onFailure { toast("Mídia indisponível.") }
            } ?: toast("Sua próxima captura aparecerá aqui.")
        }
        ui.modePhoto.setOnClickListener { selectMode(ShootingMode.PHOTO) }
        ui.modeVideo.setOnClickListener { selectMode(ShootingMode.VIDEO) }
        ui.modeNight.text = "Noturno"
        ui.modeNight.contentDescription = "Modo Noturno multiframe"
        ui.modeNight.setOnClickListener { selectMode(ShootingMode.NIGHT) }
        BurstJobs.states.observe(this) { renderStatus() }
        AutoPhotos.recover(applicationContext)
        AutoPhotos.states.observe(this) { state ->
            if (state.uri != null && prefs.getString("autoSeen", null) != state.uri.toString()) {
                remember(state.uri, state.message, state.zoom)
                prefs.edit().putString("autoSeen", state.uri.toString()).apply()
            }
            renderStatus()
        }
        ui.modePro.setOnClickListener { selectMode(ShootingMode.PRO) }
        ui.modeMore.text = "Mais"
        ui.modeMore.contentDescription = "Mais opções de fotografia"
        ui.modeMore.setOnClickListener {
            PopupMenu(this, ui.modeMore).apply {
                menu.add(0, 1, 0, "Horizon AI · super-resolução local")
                menu.add(0, 2, 1, "Diagnóstico da câmera")
                val latestJob = RestorationJobs.states.value
                if (latestJob?.saved != null) menu.add(0, 3, 2, "Comparar última restauração")
                setOnMenuItemClickListener { item ->
                    if (item.itemId != 2) startActivity(Intent(this@MainActivity, HorizonAiActivity::class.java).apply {
                        val selected = if (item.itemId == 3) latestJob?.input else null
                        data = selected?.uri ?: prefs.getString("lastJpeg", null)?.let(Uri::parse)
                        putExtra("zoom", selected?.zoom ?: prefs.getFloat("lastJpegZoom", -1f))
                    }) else showDiagnostics()
                    true
                }
                show()
            }
        }
        RestorationJobs.states.observe(this) { state ->
            ui.modeMore.text = when {
                !state.running -> "Mais"
                state.total <= 0 -> "IA…"
                else -> "IA ${100 * state.completed / state.total}%"
            }
        }
        ui.modePro.text = "Pro"
        ui.modePro.contentDescription = "Modo Pro"
        ui.recordButton.setOnClickListener { shutter() }
        ui.pauseButton.setOnClickListener {
            val active = recording ?: return@setOnClickListener
            if (!pausedCommand && !stopping) {
                pausedCommand = true
                if (paused) active.resume() else active.pause()
                haptic()
            }
        }
        ui.quickControlsButton.setOnClickListener { settings() }
        ui.lockButton.setOnClickListener { stabilizationMenu() }
        ui.qualityButton.setOnClickListener { videoQualityMenu() }
        ui.fpsButton.setOnClickListener {
            if (!busy) {
                fps = if (fps == 60) 30 else 60
                rebind()
            }
        }
        ui.flashButton.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            observeControl(cam.cameraControl.enableTorch(cam.cameraInfo.torchState.value != 1), "Lanterna")
            haptic()
        }
        ui.statusText.setOnClickListener {
            if (failure != null && !busy) retry() else showDiagnostics()
        }
        ui.statusText.setOnLongClickListener { showDiagnostics(); true }
        presets.forEach { (value, button) -> button.setOnClickListener { zoomTo(value); haptic() } }
        installGestures(ui.previewView)
        ui.zoomReadout.setOnClickListener { choosePhotoZoom() }
        ui.zoomSlider.apply {
            max = PhotoZoomScale.STEPS
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val raw = PhotoZoomScale.zoom(value, minimumPhotoZoom())
                    val desired = if (draggingZoom) zoomDetents.update(raw, SystemClock.elapsedRealtime())
                        else PhotoZoomScale.tenths(raw)
                    if (draggingZoom && desired == targetZoom && sliderGeneration == photoZoom.intent.generation) return
                    zoomTo(desired, continuous = draggingZoom)
                    if (draggingZoom) sliderGeneration = photoZoom.intent.generation
                }
                override fun onStartTrackingTouch(bar: SeekBar) {
                    draggingZoom = true
                    sliderGeneration = null
                    zoomDetents.start(targetZoom)
                }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    draggingZoom = false
                    finishPhotoZoomGesture(sliderGeneration)
                    sliderGeneration = null
                    renderZoom()
                }
            })
        }
        installGestures(ui.photoPreview)
        ui.exposureSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val cam = camera ?: return
                if (!fromUser) return
                val exposure = cam.cameraInfo.exposureState
                val index = exposure.exposureCompensationRange.lower + progress
                observeControl(cam.cameraControl.setExposureCompensationIndex(index), "Exposição")
                ui.exposureSlider.contentDescription = String.format(Locale.getDefault(), "Exposição %+.1f EV", index * exposure.exposureCompensationStep.toFloat())
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { ui.exposureSlider.removeCallbacks(hideExposure) }
            override fun onStopTrackingTouch(bar: SeekBar?) { ui.exposureSlider.postDelayed(hideExposure, 3000) }
        })
        ui.photoPreview.previewStreamState.observe(this) { state ->
            previewStreaming = state == androidx.camera.view.PreviewView.StreamState.STREAMING
            updateCameraReady()
        }
        render()
        if (hasCamera()) openCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun hasCamera() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun retry() {
        recoveryAttempt = 0
        recoveryMessage = null
        recoveredPhotoGeneration = null
        lenses?.clearRejections(lensProfile)
        if (locked && !lockLensExplicit) lockLensConfigured = false
        if (hasCamera()) openCamera() else if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        } else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun openCamera() {
        if (!hasCamera() || isDestroyed || isFinishing || busy || requestingProvider) return
        failure = null
        if (provider != null) { rebind(); return }
        requestingProvider = true
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            requestingProvider = false
            if (!isDestroyed && !isFinishing) runCatching {
                provider = future.get()
                rebind()
            }.onFailure { fail("Falha ao abrir a câmera", it) }
        }, main)
    }

    private fun selectMode(next: ShootingMode) {
        if (busy || mode == next) return
        val wasLocked = locked
        val previousZoom = displayZoom
        val previousKey = lens?.key
        displayZoom /= extraCrop
        targetZoom = displayZoom
        extraCrop = 1f
        distantFocus = false
        mode = next
        if (wasLocked && !locked) restoreLockLens()
        else if (!wasLocked && locked) {
            lockReturnKey = previousKey
            lockReturnZoom = previousZoom
            lockLensConfigured = false
            lockLensExplicit = false
        }
        burstDetail = false
        hdrRequested = false
        photoSettings = AdvancedPhotoSettings()
        prefs.edit().putString("shootingMode", next.name).apply()
        haptic()
        syncSensor()
        rebind()
    }

    private fun releaseSession(retainPhotoBinding: Boolean = false) {
        cancelPhotoClose?.invoke()
        reopen?.let(ui.root::removeCallbacks)
        openingTimeout?.let(ui.root::removeCallbacks)
        physicalVerification?.let(ui.root::removeCallbacks)
        cancelLensSwitch()
        reopen = null
        openingTimeout = null
        physicalVerification = null
        physicalConfirmed = false
        physicalCropZoom = 1f
        dragPreviewCrop = null
        ui.previewView.watchFirstFrame(null)
        cameraOpen = false
        previewStreaming = false
        photoSurfaceRequested = false
        firstFrameAt = 0L
        session++
        photoZoom.beginSession(session)
        zoomPending = false
        zoomCommand++
        inFlightNativeRatio = null
        appliedNativeRatio = null
        nextNativeRatio = null
        focusCommand++
        applyingManual = false
        camera?.cameraInfo?.apply {
            cameraState.removeObservers(this@MainActivity)
            runCatching { zoomState.removeObservers(this@MainActivity) }
            torchState.removeObservers(this@MainActivity)
        }
        ready = false
        attemptingNativeEis = false
        lenses?.invalidateCaptureDiagnostics()
        if (!retainPhotoBinding) provider?.unbindAll()
        video?.close()
        video = null
        photo = null
        camera = null
        ui.exposureSlider.isVisible = false
        ui.focusIndicator.isVisible = false
        physicalLog = null
    }

    private fun rebind(allowEis: Boolean = true, keepPhotoHandoff: Boolean = false) {
        if (busy || isDestroyed || isFinishing) return
        if (!keepPhotoHandoff) finishPhotoHandoff()
        val replacePhotoSession = keepPhotoHandoff && softwareStillZoom && photo != null && camera != null
        val bindStart = SystemClock.elapsedRealtime()
        releaseSession(retainPhotoBinding = replacePhotoSession)
        bindingStartedAt = SystemClock.elapsedRealtime()
        failure = null
        render()
        val owner = provider ?: return
        val token = session
        try {
            val manager = lenses ?: RearLensManager.discover(this, owner).also { lenses = it }
            if (locked && !lockLensExplicit && (!lockLensConfigured || lens?.let { manager.isRejected(it.key, lensProfile) } == true)) {
                if (lockReturnKey == null) { lockReturnKey = lens?.key; lockReturnZoom = targetZoom }
                val wide = manager.horizonLockRoute(lens?.key ?: manager.defaultOption.key, lensProfile)
                lens = manager.option(wide.targetKey)
                targetZoom = wide.displayZoom
                displayZoom = wide.displayZoom
                lockLensConfigured = true
            }
            val requestedLens = if (softwareStillZoom) {
                route(targetZoom, photoZoom.intent.preferred)?.let { manager.option(it.targetKey) }
            } else lens
            val choice = requestedLens ?: manager.defaultOption
            lens = choice
            zoomPending = !softwareStillZoom
            focusMessage = null
            val builder = Preview.Builder()
            val halEis = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
            manager.attachCaptureDiagnostics(builder, choice, halEis::set)
            boundRotation = ui.root.display?.rotation ?: Surface.ROTATION_0
            val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            val info = owner.availableCameraInfos.firstOrNull { Camera2CameraInfo.from(it).cameraId == choice.cameraId }
                ?: owner.getCameraInfo(choice.selector)
            capabilities = if (photoMode) null else runCatching {
                videoCapsCache.getOrPut(choice.key) { CameraCapabilities.inspect(this, info) }
            }.getOrNull()
            photoCaps = runCatching { photoCapsCache.getOrPut(choice.key) { AdvancedPhotoCapabilities.inspect(this, info, choice.physicalCameraId) } }.getOrNull()
            eisSupported = capabilities?.nativeEisDeclared == true
            val captureSelector = if (hdrRequested && photoMode) {
                val selected = hdr?.selection(choice, true)
                check(selected?.hdrEnabled == true) { selected?.reason ?: "HDR indisponível" }
                selected.selector
            } else choice.selector
            val bound = if (photoMode) {
                val previewProvider = if (softwareStillZoom) Preview.SurfaceProvider { request ->
                    if (session == token) {
                        photoSurfaceRequested = true
                        ui.photoPreview.surfaceProvider.onSurfaceRequested(request)
                        updateCameraReady()
                    } else request.willNotProvideSurface()
                } else ui.photoPreview.surfaceProvider
                val engine = PhotoEngine(boundRotation, portrait, builder, previewProvider,
                    photoSettings, photoCaps, hdrRequested, choice.physicalCameraId, choice.physicalPhotoOutputs)
                photo = engine
                if (softwareStillZoom) owner.bindToLifecycle(this, captureSelector, engine.sessionConfig)
                else owner.bindToLifecycle(this, captureSelector, engine.group)
            } else {
                val rotation = if (locked) Surface.ROTATION_90 else boundRotation
                val aspect = if (locked || !portrait) Rational(16, 9) else Rational(9, 16)
                val surface = if (locked) ui.previewView.sessionSurfaceProvider {
                    if (session == token) {
                        previewStreaming = true
                        updateCameraReady()
                    }
                } else ui.photoPreview.surfaceProvider
                val eis = allowEis && eisSupported && horizon.mode() != StabilizationMode.OFF
                attemptingNativeEis = eis
                val engine = VideoEngine(horizon, builder, surface, rotation, aspect, requestedQuality, fps,
                    eis, info, choice.physicalCameraId, halEis::get) { error ->
                    main.execute { if (session == token && !isDestroyed) {
                        if (recording != null) stopVideo()
                        fail("Falha no processamento de vídeo", error)
                    } }
                }
                video = engine
                owner.bindToLifecycle(this, captureSelector, engine.sessionConfig)
            }
            camera = bound
            manager.onBound(choice, bound)
            if ((mode == ShootingMode.PRO && photoSettings.manual != ManualPhotoSettings()) || choice.cameraId in manualOverrides) {
                photoCaps?.let { applyManual(bound, photoSettings.manual, it) }
            }
            horizon.setCameraRealtimeTimestamp(Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME)
            val resolution = photo?.capture?.resolutionInfo ?: video?.capture?.resolutionInfo
            Log.i(TAG, "session=$token mode=${if (photoMode) "foto" else "vídeo"} camera=${choice.cameraId} requestedPhysical=${choice.physicalCameraId} output=${resolution?.resolution} crop=${resolution?.cropRect} rotation=${resolution?.rotationDegrees} targetFps=$fps")
            if (keepPhotoHandoff) Log.i(TAG, "lensBind session=$token replace=$replacePhotoSession setupMs=${SystemClock.elapsedRealtime() - bindStart}")
            var initialZoomPending = !softwareStillZoom && !choice.isPhysicalOnly
            var initialExposurePending = handoffExposure
            bound.cameraInfo.torchState.observe(this) { if (session == token) ui.flashButton.isSelected = it == 1 }
            bound.cameraInfo.cameraState.observe(this) { state ->
                if (session != token) return@observe
                val code = state.error?.code
                val wasOpen = cameraOpen
                cameraOpen = state.type == CameraState.Type.OPEN && code == null
                if (cameraOpen && !wasOpen && keepPhotoHandoff) {
                    Log.i(TAG, "lensOpen session=$token openMs=${SystemClock.elapsedRealtime() - bindStart}")
                }
                if (!cameraOpen && (code != null || (softwareStillZoom && wasOpen))) {
                    previewStreaming = false
                    if (softwareStillZoom) {
                        physicalConfirmed = false
                        photoZoom.invalidate()
                        firstFrameAt = 0L
                        zoomCommand++
                        inFlightNativeRatio = null
                        appliedNativeRatio = null
                    }
                }
                if (cameraOpen) initialExposurePending?.let { ev ->
                    initialExposurePending = null
                    val exposure = bound.cameraInfo.exposureState
                    val step = exposure.exposureCompensationStep.toFloat()
                    if (exposure.isExposureCompensationSupported && step > 0f) {
                        val range = exposure.exposureCompensationRange
                        val index = (ev / step).roundToInt().coerceIn(range.lower, range.upper)
                        if (index != exposure.exposureCompensationIndex) {
                            observeControl(bound.cameraControl.setExposureCompensationIndex(index), "Exposição")
                        }
                    }
                }
                if (cameraOpen && initialZoomPending) {
                    initialZoomPending = false
                    val limits = bound.cameraInfo.zoomState.value
                    val initial = (targetZoom / choice.relativeZoom).coerceIn(limits?.minZoomRatio ?: choice.minCameraZoom,
                        limits?.maxZoomRatio ?: choice.maxCameraZoom)
                    applyCameraZoom(bound, initial)
                }
                if (code != null) {
                    Log.w(TAG, "session=$token cameraError=$code type=${state.type}", state.error?.cause)
                    when {
                        softwareStillZoom && handoffStartedAt != null && code == CameraState.ERROR_CAMERA_FATAL_ERROR ->
                            ui.root.post {
                                if (session == token && !busy) recoverPhotoHandoff(allowEis, code)
                            }
                        else -> handleCameraError(code, token, allowEis)
                    }
                }
                updateCameraReady()
                render()
            }
            val deadline = Runnable {
                if (session == token && !ready && !busy) {
                    if ((choice.isPhysicalOnly || (softwareStillZoom && choice.isStandaloneSensor)) && recoveryMessage == null)
                        fallback(allowEis, IllegalStateException("A lente não confirmou a prévia em 5 s"))
                    else scheduleRecovery(token, allowEis, -1, 0L)
                }
            }
            openingTimeout = deadline
            if (!ready) ui.root.postDelayed(deadline, 5000)
        } catch (error: Exception) { fallback(allowEis, error) }
    }

    private fun handleCameraError(code: Int, token: Int, allowEis: Boolean) {
        when (code) {
            CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE,
            CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> {
                recoveryMessage = if (code == CameraState.ERROR_CAMERA_IN_USE)
                    "Aguardando liberação da câmera…" else "Reconectando câmera…"
                scheduleRecovery(token, allowEis, code, 2200L)
            }
            CameraState.ERROR_STREAM_CONFIG -> {
                if (!busy) ui.root.post {
                    if (session == token && !busy) fallback(allowEis, IllegalStateException("Configuração recusada ($code)"))
                }
            }
            else -> failure = "Câmera indisponível · $code"
        }
    }

    private fun updateCameraReady() {
        val previewReady = cameraOpen && previewStreaming && (!softwareStillZoom || photoSurfaceRequested)
        val choice = lens
        if (previewReady && firstFrameAt == 0L) {
            firstFrameAt = SystemClock.elapsedRealtime()
            logTransition("first-preview-frame")
        }
        if (softwareStillZoom) {
            ready = previewReady && choice != null && confirmPhotoPreview(choice)
        } else if (previewReady && choice?.isPhysicalOnly == true && !physicalConfirmed) {
            val evidence = lenses?.diagnostics(lensProfile)
            if (evidence != null && evidence.outputPhysicalCameraId == choice.physicalCameraId) {
                physicalVerification?.let(ui.root::removeCallbacks)
                physicalVerification = null
                physicalConfirmed = true
                physicalCropZoom = evidence.capture?.physicalCropZoom ?: 1f
                commitZoom(choice.relativeZoom * physicalCropZoom)
            } else if (physicalVerification == null) {
                val token = session
                val check = Runnable {
                    physicalVerification = null
                    if (session != token || !cameraOpen || !previewStreaming || busy) return@Runnable
                    if (SystemClock.elapsedRealtime() - firstFrameAt >= 1000L &&
                        lenses?.diagnostics(lensProfile)?.outputPhysicalCameraId != choice.physicalCameraId) {
                        fallback(true, IllegalStateException("A prévia não confirmou o sensor físico ${choice.physicalCameraId}"))
                    } else updateCameraReady()
                }
                physicalVerification = check
                ui.root.postDelayed(check, 100L)
            }
        }
        if (!softwareStillZoom) ready = previewReady && (choice?.isPhysicalOnly != true || physicalConfirmed)
        if (ready) {
            failure = null
            recoveryMessage = null
            recoveryAttempt = 0
            reopen?.let(ui.root::removeCallbacks)
            openingTimeout?.let(ui.root::removeCallbacks)
            reopen = null
            openingTimeout = null
        }
        if (ready && softwareStillZoom) applyLatestPhotoZoom()
        if (::ui.isInitialized) render()
    }

    private fun confirmPhotoPreview(choice: LensOption): Boolean {
        if (physicalConfirmed) return true
        val evidence = lenses?.diagnostics(lensProfile)
        val capture = evidence?.capture
        val confirmed = evidence?.selectedKey == choice.key && capture != null &&
            when {
                choice.isPhysicalOnly -> evidence.outputPhysicalCameraId == choice.physicalCameraId
                choice.isStandaloneSensor -> evidence.outputPhysicalCameraId == choice.cameraId
                else -> true
            }
        if (confirmed) {
            physicalVerification?.let(ui.root::removeCallbacks)
            physicalVerification = null
            physicalCropZoom = capture?.physicalCropZoom ?: 1f
            if (appliedNativeRatio == null) {
                // Reuse the new session's measured native baseline when it already matches.
                appliedNativeRatio = capture?.nativeZoom?.appliedFor(1f)
            }
            physicalConfirmed = true
            if (distantFocus) camera?.let { applyDistantFocus(it, true) }
            return true
        }
        if (physicalVerification == null) {
            val token = session
            val check = Runnable {
                physicalVerification = null
                if (session != token || !cameraOpen || !previewStreaming || busy) return@Runnable
                val current = lenses?.diagnostics(lensProfile)
                val hasPhysicalEvidence = when {
                    choice.isPhysicalOnly -> current?.outputPhysicalCameraId == choice.physicalCameraId
                    choice.isStandaloneSensor -> current?.outputPhysicalCameraId == choice.cameraId
                    else -> current?.capture != null
                }
                if (SystemClock.elapsedRealtime() - firstFrameAt >= 1000L && !hasPhysicalEvidence) {
                    fallback(true, IllegalStateException("A prévia não confirmou ${choice.key}"))
                } else updateCameraReady()
            }
            physicalVerification = check
            ui.root.postDelayed(check, 32L)
        }
        return false
    }

    private fun scheduleRecovery(token: Int, allowEis: Boolean, code: Int, delay: Long) {
        if (reopen != null || isDestroyed || isFinishing || busy) return
        val backoff = StillPreviewGeometry.retryDelay(recoveryAttempt)
        if (backoff == null) {
            if (attemptingNativeEis || lens?.let { it != lenses?.defaultOption } == true) {
                ui.root.post {
                    if (session == token && !busy && !ready) {
                        recoveryAttempt = 0
                        recoveryMessage = null
                        fallback(allowEis, IllegalStateException("A lente física não reabriu ($code)"))
                    }
                }
                return
            }
            failure = "Câmera ocupada ou sem resposta · toque para tentar"
            recoveryMessage = null
            return
        }
        val task = Runnable {
            reopen = null
            if (session != token || busy || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@Runnable
            recoveryAttempt++
            Log.i(TAG, "recover session=$token code=$code attempt=$recoveryAttempt")
            rebind(allowEis, keepPhotoHandoff = handoffStartedAt != null)
        }
        reopen = task
        ui.root.postDelayed(task, maxOf(delay, backoff))
    }

    private fun fallback(allowEis: Boolean, error: Throwable) {
        val manager = lenses
        val choice = lens
        Log.w(TAG, "Configuração recusada ${choice?.key}", error)
        recoveryMessage = null
        when {
            !photoMode && allowEis && attemptingNativeEis -> {
                toast(if (locked) "EIS nativo recusado. Mantendo Horizon Lock nesta lente."
                    else "EIS nativo recusado. Tentando a mesma resolução e FPS sem EIS.")
                rebind(false)
            }
            photoMode && (photoSettings != AdvancedPhotoSettings() || hdrRequested) -> {
                if (hdrRequested && choice != null) hdr?.onBindRejected(choice, error.message ?: "Sessão HDR recusada")
                photoSettings = AdvancedPhotoSettings()
                hdrRequested = false
                toast("Esta combinação de foto foi recusada. Voltando ao JPEG automático.")
                rebind(keepPhotoHandoff = handoffStartedAt != null)
            }
            manager != null && choice != null && choice != manager.defaultOption -> {
                manager.onBindRejected(choice, lensProfile, error.message ?: "configuração")
                val alternative = if (locked && !lockLensExplicit && targetZoom < 1f) {
                    manager.horizonLockRoute(choice.key, lensProfile)
                } else route(targetZoom)
                lens = alternative?.let { manager.option(it.targetKey) } ?: manager.defaultOption
                toast(if (lens == manager.defaultOption) "Usando a câmera automática neste modo."
                    else "Tentando outra câmera para este enquadramento.")
                rebind(keepPhotoHandoff = handoffStartedAt != null)
            }
            !photoMode && fps != 30 -> { fps = 30; toast("Gravação disponível a 30 fps."); rebind() }
            !photoMode && requestedQuality == Quality.UHD -> {
                requestedQuality = Quality.FHD; toast("4K indisponível nesta combinação."); rebind()
            }
            else -> { releaseSession(); fail("Falha de câmera · toque para tentar", error) }
        }
    }

    private fun route(requested: Float, preferred: String? = null): ZoomRoute? {
        val manager = lenses ?: return null
        val choice = lens ?: manager.defaultOption
        val state = if (choice.isPhysicalOnly) null else camera?.cameraInfo?.zoomState?.value
        // The S24 can drop device 0 when reconfiguring from 0/6 to pinned 0/5.
        // Its logical output supplies the same main field of view at native 1x.
        val destination = if (usesS24LogicalMain(requested, preferred)) manager.defaultOption.key else preferred
        return manager.route(requested, choice.key, state?.minZoomRatio ?: choice.minCameraZoom,
            state?.maxZoomRatio ?: choice.maxCameraZoom, recording != null, destination,
            stillPhoto = softwareStillZoom, profile = lensProfile)
    }

    private fun minimumPhotoZoom(): Float =
        (route(0.6f)?.displayZoom ?: 1f).coerceIn(0.6f, 1f)

    private fun zoomTo(requested: Float, preferred: String? = null, continuous: Boolean = false) {
        if (!canZoom || takingPhoto || awaitingMic || stopping) return
        if (softwareStillZoom) {
            val desired = if (preferred == null) requested.coerceIn(0.6f, StillFraming.MAX_ZOOM) else requested
            photoZoom.request(desired, preferred, continuous, SystemClock.elapsedRealtime())
            applyLatestPhotoZoom()
            renderZoom()
            renderStatus()
            return
        }
        val route = route(requested, preferred) ?: return
        if (route.requiresRebind && busy) return
        targetZoom = requested
        cancelLensSwitch()
        if (route.requiresRebind) {
            zoomCommand++
            inFlightNativeRatio = null
            nextNativeRatio = null
            zoomPending = true
            val token = session
            val next = lenses?.option(route.targetKey) ?: return
            val changeLens = Runnable {
                lensSwitch = null
                if (session == token && !busy && !isDestroyed) {
                    lens = next
                    rebind()
                }
            }
            lensSwitch = changeLens
            if (continuous) ui.root.postDelayed(changeLens, 65L)
            else ui.root.postOnAnimation(changeLens)
        } else if (lens?.isPhysicalOnly == true) {
            commitZoom(route.displayZoom * physicalCropZoom)
        } else camera?.let { current ->
            val ratio = route.cameraZoomRatio
            val active = inFlightNativeRatio
            val applied = appliedNativeRatio
            when {
                active != null -> nextNativeRatio = ratio.takeIf { abs(it - active) > 0.001f }
                applied != null && abs(ratio - applied) <= 0.001f ->
                    commitZoom(applied * (lens?.relativeZoom ?: 1f), log = !continuous)
                else -> applyCameraZoom(current, ratio)
            }
        }
        renderZoom()
        renderStatus()
    }

    private fun finishPhotoZoomGesture(generation: Long?) {
        if (photoZoom.finishGesture(generation)) applyLatestPhotoZoom()
    }

    private fun cancelLensSwitch() {
        lensSwitch?.let(ui.root::removeCallbacks)
        lensSwitch = null
    }

    private fun applyLatestPhotoZoom(failedNativeRatio: Float? = null) {
        if (!ready || busy || !photoZoom.isPending) return
        val intent = photoZoom.intent
        val choice = lens ?: return
        val current = camera ?: return
        val route = route(intent.total, intent.preferred) ?: return
        val native = appliedNativeRatio
        val supplied = if (choice.isPhysicalOnly) choice.relativeZoom * physicalCropZoom
            else native?.let { choice.relativeZoom * it }

        if (route.requiresRebind) {
            if (supplied != null && inFlightNativeRatio == null && handoffStartedAt == null) {
                dragPreviewCrop = StillFraming.digitalRatio(intent.total, supplied)
            }
            schedulePhotoLensSwitch(intent)
            return
        }
        cancelLensSwitch()

        if (choice.isPhysicalOnly && supplied != null) {
            if (supplied > intent.total * 1.02f) {
                val token = session
                ui.root.post {
                    if (session == token && photoZoom.intent.generation == intent.generation && !busy) {
                        fallback(true, IllegalStateException("A saída física entregou ${formatZoom(supplied)} para ${formatZoom(intent.total)}."))
                    }
                }
                return
            }
        } else {
            // A standalone sensor supplies a fixed base for the shared preview/JPEG crop.
            // The logical fallback can still use hardware zoom.
            val anchorMain = usesS24LogicalMain(intent.total, intent.preferred)
            val ratio = if (choice.isStandaloneSensor || anchorMain) current.cameraInfo.zoomState.value?.let {
                1f.coerceIn(it.minZoomRatio, it.maxZoomRatio)
            } ?: choice.minCameraZoom else route.cameraZoomRatio
            if (inFlightNativeRatio != null) return
            if (native == null || (abs(native - ratio) > 0.001f && failedNativeRatio != ratio)) {
                applyCameraZoom(current, ratio)
                return
            }
            if (anchorMain) {
                val evidence = lenses?.diagnostics(lensProfile)
                val physicalId = evidence?.outputPhysicalCameraId
                val focal = evidence?.capture?.focalLength
                val mainConfirmed = physicalId == "5" || (physicalId == null && focal != null && abs(focal - 5.4f) < 0.1f)
                if (!mainConfirmed) {
                    val token = session
                    ui.root.post {
                        if (session == token && photoZoom.intent.generation == intent.generation && !busy) {
                            fallback(true, IllegalStateException("O fluxo lógico não confirmou a câmera principal."))
                        }
                    }
                    return
                }
            }
        }
        if (supplied != null && photoZoom.confirm(session, intent.generation)) {
            commitZoom(supplied, log = !intent.continuous || handoffStartedAt != null)
        }
    }

    private fun schedulePhotoLensSwitch(intent: PhotoZoomTransition.Intent) {
        if (lensSwitch != null && intent.continuous) return
        cancelLensSwitch()
        val token = session
        val change = object : Runnable {
            override fun run() {
                if (lensSwitch !== this) return
                if (session != token || busy || isDestroyed || !ready ||
                    !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    cancelLensSwitch()
                    return
                }
                val latest = photoZoom.intent
                val remaining = latest.switchNotBefore - SystemClock.elapsedRealtime()
                if (remaining > 0) {
                    ui.root.postDelayed(this, remaining)
                    return
                }
                lensSwitch = null
                val destination = route(latest.total, latest.preferred)?.takeIf { it.requiresRebind } ?: return
                val next = lenses?.option(destination.targetKey) ?: return
                holdPhotoFrame()
                Log.i(TAG, "lensIntent generation=${latest.generation} requested=${latest.total} route=${next.key}")
                val source = lens
                if (source?.isPhysicalOnly == true && next.cameraId == source.cameraId) {
                    reopenPhotoAfterClose()
                } else {
                    rebind(keepPhotoHandoff = true)
                }
            }
        }
        lensSwitch = change
        val delay = intent.switchNotBefore - SystemClock.elapsedRealtime()
        if (delay > 0) ui.root.postDelayed(change, delay) else ui.root.postOnAnimation(change)
    }

    private fun recoverPhotoHandoff(allowEis: Boolean, code: Int) {
        val generation = photoZoom.intent.generation
        if (recoveredPhotoGeneration == generation) {
            fallback(allowEis, IllegalStateException("Sessão de foto interrompida ($code)"))
            return
        }
        recoveredPhotoGeneration = generation
        reopenPhotoAfterClose()
    }

    private fun reopenPhotoAfterClose() {
        val cameraId = lens?.cameraId ?: return
        val manager = getSystemService(CameraManager::class.java)
        releaseSession()
        val token = session
        val started = SystemClock.elapsedRealtime()
        var cameraAvailable = false
        val resume = object : Runnable {
            override fun run() {
                if (session != token || cancelPhotoClose == null) return
                val intent = photoZoom.intent
                val destination = route(intent.total, intent.preferred)?.let { lenses?.option(it.targetKey) }
                val destinationId = destination?.cameraId ?: cameraId
                val elapsed = SystemClock.elapsedRealtime() - started
                val knownToCameraX = provider?.availableCameraInfos?.any {
                    Camera2CameraInfo.from(it).cameraId == destinationId
                } == true
                // After a provider restart, the framework can report availability before
                // CameraX has repopulated its selectable camera list.
                if (knownToCameraX && (cameraAvailable || elapsed >= 3_000L)) {
                    cancelPhotoClose?.invoke()
                    Log.i(TAG, "lensClose session=$token available=$cameraAvailable closeMs=$elapsed")
                    rebind(keepPhotoHandoff = true)
                } else if (elapsed >= 4_500L) {
                    cancelPhotoClose?.invoke()
                    fail("Câmera indisponível · toque para tentar",
                        IllegalStateException("CameraX não voltou a listar a câmera $destinationId"))
                } else {
                    ui.root.postDelayed(this, 80L)
                }
            }
        }
        // CameraX can report CLOSED while its Camera2 device is still retained.
        val availability = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(id: String) {
                if (id != cameraId) return
                cameraAvailable = true
                ui.root.removeCallbacks(resume)
                ui.root.post(resume)
            }
        }
        cancelPhotoClose = {
            manager.unregisterAvailabilityCallback(availability)
            ui.root.removeCallbacks(resume)
            cancelPhotoClose = null
        }
        manager.registerAvailabilityCallback(main, availability)
        ui.root.postDelayed(resume, 3_000L)
        render()
    }

    private fun holdPhotoFrame() {
        if (handoffStartedAt != null) return
        handoffStartedAt = SystemClock.elapsedRealtime()
        handoffExposure = camera?.cameraInfo?.exposureState?.takeIf { it.isExposureCompensationSupported }
            ?.let { it.exposureCompensationIndex * it.exposureCompensationStep.toFloat() }
        val frame = if (previewStreaming) runCatching { ui.photoPreview.bitmap }.getOrNull() else null
        Log.i(TAG, "lensHandoffFrame held=${frame != null} copyMs=${SystemClock.elapsedRealtime() - checkNotNull(handoffStartedAt)}")
        if (frame == null) return
        ui.lensTransitionFrame.apply {
            animate().cancel()
            alpha = 1f
            scaleX = dragPreviewCrop ?: extraCrop
            scaleY = scaleX
            setImageBitmap(frame)
            isVisible = true
        }
    }

    private fun finishPhotoHandoff(logLive: Boolean = false) {
        val started = handoffStartedAt
        handoffStartedAt = null
        handoffExposure = null
        if (started != null && logLive) {
            Log.i(TAG, "lensHandoff session=$session zoom=$displayZoom liveMs=${SystemClock.elapsedRealtime() - started}")
        }
        ui.lensTransitionFrame.apply {
            isVisible = false
            setImageDrawable(null)
        }
    }

    private fun commitZoom(suppliedZoom: Float, log: Boolean = true) {
        dragPreviewCrop = null
        extraCrop = if (softwareStillZoom) StillFraming.digitalRatio(targetZoom, suppliedZoom) else 1f
        displayZoom = suppliedZoom * extraCrop
        zoomPending = false
        if (softwareStillZoom && handoffStartedAt != null) finishPhotoHandoff(logLive = true)
        renderZoom()
        if (log) logTransition("zoom-confirmed")
        renderStatus()
    }

    private fun logTransition(event: String) {
        val details = lenses?.diagnostics(lensProfile)
        val resolution = photo?.capture?.resolutionInfo ?: video?.capture?.resolutionInfo
        Log.i(TAG, "transition=$event t=${SystemClock.elapsedRealtime()} session=$session mode=$mode " +
            "camera=${details?.selectedCameraId} physicalRequested=${details?.selectedPhysicalCameraId} " +
            "physicalActive=${details?.activePhysicalCameraId} physicalOutput=${details?.outputPhysicalCameraId} " +
            "requested=$targetZoom applied=$displayZoom " +
            "extraCrop=$extraCrop previewScale=${ui.photoPreview.scaleX} buffer=${resolution?.resolution} " +
            "viewportCrop=${resolution?.cropRect} readyMs=${if(firstFrameAt>0) firstFrameAt-bindingStartedAt else null} " +
            "previewStreaming=$previewStreaming capture=${details?.capture}")
    }

    private fun applyCameraZoom(cam: Camera, ratio: Float, unavailableRatio: Float? = null) {
        val ticket = ++zoomCommand
        val token = session
        val started = SystemClock.elapsedRealtime()
        val previousFrame = lenses?.diagnostics(lensProfile)?.capture?.frame ?: -1L
        inFlightNativeRatio = ratio
        if (!softwareStillZoom) zoomPending = true
        val applied = runCatching { cam.cameraControl.setZoomRatio(ratio) }.getOrElse { error ->
            zoomRejected(cam, error, unavailableRatio ?: ratio)
            return
        }
        renderZoom()
        if (autoJpeg) ui.recordButton.isEnabled = false
        applied.addListener({
            if (ticket != zoomCommand || token != session || isDestroyed) return@addListener
            runCatching { applied.get() }.onSuccess {
                fun confirm() {
                    if (ticket != zoomCommand || token != session || isDestroyed) return
                    val info = lenses?.diagnostics(lensProfile)
                    val observed = info?.nativeZoomAfter(ratio, started, previousFrame)
                    if (observed == null && SystemClock.elapsedRealtime() - started < 1200L) {
                        ui.root.postDelayed({ confirm() }, 60)
                        return
                    }
                    if (observed == null && softwareStillZoom && unavailableRatio == null && ratio > 1.001f) {
                        // Do not treat ZoomState as HAL evidence for a requested crop.
                        applyCameraZoom(cam, 1f, unavailableRatio = ratio)
                        return
                    }
                    val actual = observed ?: cam.cameraInfo.zoomState.value?.zoomRatio ?: ratio
                    inFlightNativeRatio = null
                    appliedNativeRatio = actual
                    if (softwareStillZoom) {
                        applyLatestPhotoZoom(unavailableRatio ?: ratio)
                    } else {
                        val next = nextNativeRatio
                        nextNativeRatio = null
                        if (next != null && abs(next - actual) > 0.001f) {
                            applyCameraZoom(cam, next)
                            return
                        }
                        commitZoom(actual * (lens?.relativeZoom ?: 1f))
                    }
                    Log.i(TAG, "zoomCallback session=$token command=$ticket requestedNative=$ratio " +
                        "hal=$observed cameraX=${cam.cameraInfo.zoomState.value?.zoomRatio} total=$displayZoom " +
                        "elapsedMs=${SystemClock.elapsedRealtime()-started}")
                    render()
                }
                confirm()
            }.onFailure {
                zoomRejected(cam, it, unavailableRatio ?: ratio)
                Log.w(TAG, "Zoom recusado session=$token command=$ticket", it)
            }
            render()
        }, main)
    }

    private fun zoomRejected(cam: Camera, error: Throwable, rejectedRatio: Float) {
        val limits = cam.cameraInfo.zoomState.value
        val scale = lens?.relativeZoom ?: 1f
        val applied = limits?.zoomRatio?.takeIf { it.isFinite() && it > 0f } ?: 1f
        inFlightNativeRatio = null
        appliedNativeRatio = applied
        if (softwareStillZoom) applyLatestPhotoZoom(rejectedRatio)
        else {
            val next = nextNativeRatio
            nextNativeRatio = null
            if (next != null && abs(next - applied) > 0.001f) {
                applyCameraZoom(cam, next)
                return
            }
            commitZoom(applied * scale)
        }
        if (error !is androidx.camera.core.CameraControl.OperationCanceledException) {
            toast("A câmera recusou a ampliação solicitada.")
        }
    }

    private fun choosePhotoZoom() {
        if (!softwareStillZoom || busy || !ready) return
        val min = kotlin.math.ceil((route(0.01f)?.displayZoom ?: 1f) * 10 - 0.001f).toInt().coerceIn(6, 300)
        val slider = SeekBar(this).apply {
            max = 300 - min
            progress = (displayZoom * 10 + 0.5f).toInt().coerceIn(min, 300) - min
            contentDescription = "Zoom em décimos"
        }
        val dialog = AlertDialog.Builder(this).setTitle(formatZoom(displayZoom)).setView(slider)
            .setPositiveButton("Aplicar") { _, _ -> zoomTo((min + slider.progress) / 10f) }
            .setNegativeButton("Cancelar", null).create()
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { dialog.setTitle(formatZoom((min + value) / 10f)) }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        dialog.show()
    }

    private fun observeControl(future: com.google.common.util.concurrent.ListenableFuture<*>, name: String) {
        val token = session
        future.addListener({
            if (token == session && !isDestroyed) runCatching { future.get() }.onFailure {
                val cause = it.cause ?: it
                if (cause !is androidx.camera.core.CameraControl.OperationCanceledException) {
                    Log.w(TAG, "$name indisponível", cause)
                    toast("$name indisponível nesta câmera.")
                }
            }
        }, main)
    }

    private fun installGestures(view: View) {
        var touchedWithTwo = false
        var focusX = Float.NaN
        var focusY = Float.NaN
        var pinchGeneration: Long? = null
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchGeneration = null
                pinchZoom = if (softwareStillZoom) targetZoom else displayZoom
                return canZoom
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinchZoom = (pinchZoom * detector.scaleFactor).coerceIn(0.05f, 1000f)
                if (autoJpeg) pinchZoom = pinchZoom.coerceAtMost(StillFraming.MAX_ZOOM)
                zoomTo(pinchZoom, continuous = true)
                if (softwareStillZoom) pinchGeneration = photoZoom.intent.generation
                return true
            }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (softwareStillZoom) finishPhotoZoomGesture(pinchGeneration) else zoomTo(pinchZoom)
            }
        }).apply { isQuickScaleEnabled = false }
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!touchedWithTwo) { focusX = e.x; focusY = e.y; view.performClick() }
                return true
            }
        })
        view.setOnClickListener {
            focusAt(if (focusX.isNaN()) view.width / 2f else focusX, if (focusY.isNaN()) view.height / 2f else focusY)
            focusX = Float.NaN; focusY = Float.NaN
        }
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touchedWithTwo = false
            if (event.pointerCount > 1) touchedWithTwo = true
            scale.onTouchEvent(event)
            tap.onTouchEvent(event)
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        if (!ready || zoomInProgress || takingPhoto) return
        val cam = camera ?: return
        if (distantFocus && autoJpeg) {
            applyDistantFocus(cam, false) { focusAt(x,y) }
            return
        }
        val w = ui.previewContainer.width.coerceAtLeast(1).toFloat()
        val h = ui.previewContainer.height.coerceAtLeast(1).toFloat()
        val manualFocus = mode == ShootingMode.PRO && photoSettings.manual.focusDistanceDiopters != null
        val manualExposure = mode == ShootingMode.PRO && photoSettings.manual.iso != null
        val point = if (!locked) ui.photoPreview.meteringPointFactory.createPoint(x, y,
            StillPreviewGeometry.meteringSize(extraCrop, precise = photoMode && displayZoom >= 3f)) else {
            val displayed = video?.effect?.processor?.previewCorrection ?: return
            val p = displayed.undo(2.0 * x / w - 1, 1 - 2.0 * y / h)
            SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(
                ((1 + p.first) / 2).toFloat().coerceIn(0f, 1f), ((1 - p.second) / 2).toFloat().coerceIn(0f, 1f))
        }
        val af = if (manualFocus || lens?.autofocus == false) 0 else FocusMeteringAction.FLAG_AF
        val ae = if (manualExposure) 0 else FocusMeteringAction.FLAG_AE
        val flags = af or ae
        if (flags == 0) return
        fun action(regions: Int) = FocusMeteringAction.Builder(point, regions)
            .setAutoCancelDuration(if (photoMode && displayZoom >= 3f) 8 else 3, TimeUnit.SECONDS).build()
        val metering = listOf(flags, af, ae).distinct().filter { it != 0 }.firstNotNullOfOrNull { regions ->
            action(regions).takeIf(cam.cameraInfo::isFocusMeteringSupported)?.let { regions to it }
        }
        if (metering != null) {
            val (regions, request) = metering
            val autofocus = regions and FocusMeteringAction.FLAG_AF != 0
            val token = session
            val command = ++focusCommand
            val start = SystemClock.elapsedRealtime()
            focusMessage = if (autofocus) "Focando…" else "Medindo exposição…"
            val future = cam.cameraControl.startFocusAndMetering(request)
            future.addListener({
                if (token != session || command != focusCommand || isDestroyed) return@addListener
                runCatching { future.get() }.fold(
                    onSuccess = { focusMessage = when {
                        !autofocus -> "Exposição medida"
                        it.isFocusSuccessful -> "Foco confirmado"
                        else -> "Foco não confirmado"
                    } },
                    onFailure = { focusMessage = if (autofocus) "Foco interrompido" else null })
                Log.i(TAG, "focusCallback session=$token elapsedMs=${SystemClock.elapsedRealtime()-start} result=$focusMessage capture=${lenses?.diagnostics(lensProfile)?.capture}")
                renderStatus()
                ui.focusIndicator.postDelayed({
                    if (token == session && command == focusCommand && !distantFocus) { focusMessage = null; renderStatus() }
                }, 2200)
            }, main)
        }
        ui.focusIndicator.apply {
            translationX = (if (locked) x else StillPreviewGeometry.localToVisible(x,w,extraCrop)) - w / 2
            translationY = (if (locked) y else StillPreviewGeometry.localToVisible(y,h,extraCrop)) - h / 2
            isVisible = true
            removeCallbacks(hideFocus)
            postDelayed(hideFocus, 900)
        }
        val ev = cam.cameraInfo.exposureState
        if (!manualExposure && ev.isExposureCompensationSupported) ui.exposureSlider.apply {
            max = ev.exposureCompensationRange.upper - ev.exposureCompensationRange.lower
            progress = ev.exposureCompensationIndex - ev.exposureCompensationRange.lower
            isVisible = true
            removeCallbacks(hideExposure)
            postDelayed(hideExposure, 3500)
        }
        haptic()
    }
    private val hideFocus = Runnable { ui.focusIndicator.isVisible = false }
    private val hideExposure = Runnable { ui.exposureSlider.isVisible = false }

    private fun shutter() {
        if (recording != null) { stopVideo(); return }
        if (!ready || busy || failure != null || zoomInProgress) return
        if (mode == ShootingMode.NIGHT || (mode == ShootingMode.PHOTO && burstDetail && extraCrop == 1f)) {
            startBurst()
            return
        }
        haptic()
        if (photoMode) {
            val engine = photo ?: return
            takingPhoto = true
            render()
            if (mode == ShootingMode.PHOTO && photoSettings.format == PhotoFormat.JPEG) {
                val trace = lenses?.diagnostics(lensProfile)
                Log.i(TAG, "Disparo zoom=$displayZoom recorte=$extraCrop camera=${trace?.selectedCameraId} fisicaSolicitada=${trace?.selectedPhysicalCameraId} ativaHAL=${trace?.activePhysicalCameraId}")
                val captureEpoch = session
                val captureCamera = camera
                val captureKey = lens?.key
                val captureZoom = displayZoom
                val captureCrop = extraCrop
                fun currentCaptureSensor(): String? {
                    if (session != captureEpoch || camera !== captureCamera || zoomInProgress ||
                        abs(displayZoom - captureZoom) > 0.0001f || abs(extraCrop - captureCrop) > 0.0001f) return null
                    val current = lenses?.diagnostics(lensProfile) ?: return null
                    if (current.selectedKey != captureKey || current.capture == null) return null
                    return current.outputPhysicalCameraId
                }
                val physical = currentCaptureSensor()
                AutoPhotos.capture(applicationContext, engine.capture, captureZoom, captureCrop, main,
                    physicalId = ::currentCaptureSensor, boundSensor = physical) { error ->
                    takingPhoto = false
                    if (!isDestroyed) {
                        if (error != null) toast("Falha ao guardar a captura: ${error.localizedMessage}")
                        afterCapture()
                    }
                }
                return
            }
            val done: (Result<Uri>) -> Unit = { result ->
                takingPhoto = false
                result.onSuccess { uri -> remember(uri, "Foto salva") }
                    .onFailure { Log.e(TAG, "Falha ao salvar JPEG", it); if (!isDestroyed) toast("Falha ao salvar foto.") }
                if (!isDestroyed) afterCapture()
            }
            try { engine.shoot(contentResolver, main, done) } catch (e: Exception) { done(Result.failure(e)) }
        } else if (audio && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            awaitingMic = true
            render()
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else beginVideo()
    }

    private fun startBurst() {
        val cam = camera ?: return
        val capture = photo?.capture ?: return
        if (BurstJobs.states.value?.running == true || RestorationJobs.states.value?.running == true) {
            toast("Aguarde a conclusão do processamento em andamento.")
            return
        }
        takingPhoto = true
        burstZoom = displayZoom
        burstProgress = "Preparando sequência…"
        render()
        haptic()
        val reference = runCatching { AutoPhotos.beginBurst(applicationContext, burstZoom) }.getOrElse {
            takingPhoto = false
            toast("Espaço privado indisponível para a sequência.")
            render()
            return
        }
        val sequence = BurstCapture(cacheDir, reference, cam, capture, main, mode == ShootingMode.NIGHT,
            { lenses?.diagnostics(lensProfile)?.outputPhysicalCameraId },
            { completed, total -> burstProgress = "Capturando · $completed/$total"; renderStatus() },
            { result ->
                burst = null
                takingPhoto = false
                result.onSuccess { frames -> BurstJobs.process(applicationContext, frames, burstDetail, burstZoom) }
                    .onFailure { error ->
                        AutoPhotos.releaseBurst(applicationContext, reference)
                        if (!isDestroyed && error !is java.util.concurrent.CancellationException) {
                            Log.w(TAG, "Sequência interrompida", error)
                            toast("A captura da sequência foi interrompida.")
                        }
                    }
                if (!isDestroyed) afterCapture()
            })
        burst = sequence
        sequence.start()
    }

    private fun beginVideo() {
        val engine = video ?: return
        if ((power?.currentThermalStatus ?: 0) >= android.os.PowerManager.THERMAL_STATUS_CRITICAL) {
            toast("Aguarde o aparelho resfriar para gravar.")
            return
        }
        if (locked && !horizon.orientationFresh(SystemClock.elapsedRealtimeNanos())) {
            toast("Aponte a câmera para a cena e aguarde a referência de gravidade.")
            render()
            return
        }
        paused = false
        stopping = false
        pausedCommand = false
        ui.timerText.text = "00:00"
        try {
            horizon.startRecording()
            recording = engine.start(this, audio) { event ->
                when (event) {
                    is VideoRecordEvent.Finalize -> {
                        recording?.close()
                        recording = null
                        stopping = false
                        paused = false
                        pausedCommand = false
                        horizon.stopRecording()
                        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) horizon.clearOrientation()
                        val uri = event.outputResults.outputUri
                        if (uri != Uri.EMPTY && (!event.hasError() ||
                            event.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE ||
                            event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED ||
                            event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED)) {
                            remember(uri, "Vídeo salvo")
                        } else {
                            Log.e(TAG, "Finalize error=${event.error}", event.cause)
                            if (!isDestroyed) toast("Falha ao finalizar vídeo (${event.error}).")
                        }
                        if (!isDestroyed) afterCapture()
                    }
                    else -> if (!isDestroyed) {
                        val seconds = event.recordingStats.recordedDurationNanos / 1_000_000_000L
                        ui.timerText.text = String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
                        when (event) {
                            is VideoRecordEvent.Pause -> { paused = true; pausedCommand = false; render() }
                            is VideoRecordEvent.Resume -> { paused = false; pausedCommand = false; render() }
                            is VideoRecordEvent.Start -> {
                                Log.i(TAG, "recordStart session=$session camera=${lens?.key} ${engine.diagnostics()}")
                                render()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            horizon.stopRecording()
            Log.e(TAG, "Recorder.start", e)
            toast("O gravador não conseguiu iniciar.")
        }
        render()
    }

    private fun remember(uri: Uri, message: String, captureZoom: Float = displayZoom) {
        library.remember(uri)
        if (contentResolver.getType(uri) == "image/jpeg") {
            prefs.edit().putString("lastJpeg", uri.toString()).putFloat("lastJpegZoom", captureZoom).apply()
        }
        if (!isDestroyed && !isFinishing) {
            library.inspectAndLoad(uri, ui.galleryThumbnail::setImageBitmap)
            toast(message)
        }
    }

    private fun stopVideo() {
        if (stopping) return
        stopping = true
        recording?.stop()
        render()
    }

    private fun afterCapture() {
        if (!locked && ui.root.display?.rotation != boundRotation && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            rebind()
        } else render()
    }

    private fun videoQualityMenu() {
        if (busy) return
        val cap = capabilities ?: return
        PopupMenu(this, ui.qualityButton).apply {
            menu.add(0, 1, 0, "Full HD · 1080p").isEnabled = cap.fhd
            menu.add(0, 2, 1, "Ultra HD · 4K").isEnabled = cap.uhd
            setOnMenuItemClickListener {
                requestedQuality = if (it.itemId == 2) Quality.UHD else Quality.FHD
                if (!cap.supportsFps(requestedQuality, fps)) {
                    fps = 30
                    toast("60 fps indisponível nesta resolução. Usando 30 fps.")
                }
                rebind()
                true
            }
            show()
        }
    }

    private fun stabilizationMenu() {
        if (busy) return
        PopupMenu(this, ui.lockButton).apply {
            menu.add(0, 1, 0, "Desligada")
            menu.add(0, 2, 1, "Estabilização nativa").isEnabled = eisSupported
            menu.add(0, 3, 2, "Horizon Lock · 360°").isEnabled = sensor.isAvailable
            setOnMenuItemClickListener {
                val mode = when (it.itemId) {
                    2 -> StabilizationMode.STEADY
                    3 -> StabilizationMode.HORIZONTAL_LOCK
                    else -> StabilizationMode.OFF
                }
                val wasLocked = locked
                horizon.setMode(mode)
                if (!wasLocked && locked) {
                    lockReturnKey = lens?.key
                    lockReturnZoom = displayZoom
                    lockLensConfigured = false
                    lockLensExplicit = false
                } else if (wasLocked && !locked) restoreLockLens()
                prefs.edit().putString("stabilization", mode.name).apply()
                syncSensor()
                rebind()
                haptic()
                true
            }
            show()
        }
    }

    private fun restoreLockLens() {
        val manager = lenses
        val previous = manager?.let { m -> lockReturnKey?.let { key -> m.option(key)?.takeUnless { m.isRejected(key, lensProfile) } } }
        lens = previous ?: manager?.defaultOption
        targetZoom = lockReturnZoom
        displayZoom = targetZoom
        extraCrop = 1f
        lockLensConfigured = false
        lockLensExplicit = false
        lockReturnKey = null
    }

    private fun settings() {
        PopupMenu(this, ui.quickControlsButton).apply {
            menu.add(0, 1, 0, if (ui.gridView.isVisible) "Ocultar grade" else "Mostrar grade")
            if (!photoMode) menu.add(0, 2, 1, if (audio) "Desligar microfone" else "Ligar microfone")
            menu.add(0, 3, 2, "Escolher câmera traseira").isEnabled = ready && !busy
            menu.add(0, 4, 3, "Diagnóstico da sessão")
            if (photoMode && mode != ShootingMode.NIGHT) menu.add(0, 6, 4, "Formato e resolução").isEnabled = ready && !busy
            if (mode == ShootingMode.PRO) menu.add(0, 7, 5, "Controles manuais").isEnabled = ready && !busy
            if (mode == ShootingMode.PHOTO) menu.add(0, 8, 6, if (hdrRequested) "Desligar HDR" else "Verificar HDR da câmera").isEnabled = ready && !busy
            if (mode == ShootingMode.PHOTO) menu.add(0, 9, 7,
                if (burstDetail) "Desligar fusão de detalhe" else "Ativar fusão de detalhe · 5 quadros").isEnabled = ready && !busy
            if (autoJpeg && !hdrRequested) menu.add(0, 11, 9,
                if (distantFocus) "Voltar ao foco automático" else "Foco distante").isEnabled = ready && !busy &&
                    photoCaps?.manual?.supportsManualFocus == true
            if (BurstJobs.states.value?.running == true) menu.add(0, 10, 8, "Cancelar processamento da sequência")
            if (failure != null) menu.add(0, 5, 4, "Tentar abrir a câmera")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> { ui.gridView.isVisible = !ui.gridView.isVisible; prefs.edit().putBoolean("grid", ui.gridView.isVisible).apply() }
                    2 -> { audio = !audio; prefs.edit().putBoolean("sound", audio).apply(); render() }
                    3 -> lensMenu()
                    4 -> showDiagnostics()
                    5 -> retry()
                    6 -> photoFormats()
                    7 -> manualDialog()
                    8 -> toggleHdr()
                    9 -> {
                        burstDetail = !burstDetail
                        hdrRequested = false
                        photoSettings = AdvancedPhotoSettings()
                        rebind()
                    }
                    10 -> BurstJobs.cancel()
                    11 -> camera?.let { applyDistantFocus(it, !distantFocus) }
                }
                true
            }
            show()
        }
    }

    private fun photoFormats() {
        val caps = photoCaps ?: return
        photoDialogs.format(caps, photoSettings) { settings ->
            if (!busy && photoMode) {
                photoSettings = settings
                burstDetail = false
                hdrRequested = false
                rebind()
            }
        }
    }

    private fun applyDistantFocus(cam: Camera, enabled: Boolean, onApplied: (() -> Unit)? = null) {
        if (!autoJpeg || hdrRequested || photoCaps?.manual?.supportsManualFocus != true) return
        val request = CaptureRequestOptions.Builder()
        if (enabled) {
            request.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            request.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
        }
        val token = session
        val command = Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(request.build())
        command.addListener({
            if (session != token || isDestroyed) return@addListener
            runCatching { command.get() }.onSuccess {
                distantFocus = enabled
                focusMessage = if (enabled) "Foco distante" else null
                if (enabled) manualOverrides.add(lens?.cameraId.orEmpty()) else manualOverrides.remove(lens?.cameraId)
                onApplied?.invoke()
            }.onFailure { toast("Foco distante indisponível."); Log.w(TAG, "Focus override rejected",it) }
            renderStatus()
        }, main)
    }

    private fun manualDialog() {
        val caps = photoCaps ?: return
        val cam = camera ?: return
        val token = session
        photoDialogs.manual(caps, photoSettings.manual) { manual ->
            if (session == token && !busy && mode == ShootingMode.PRO) {
                applyManual(cam, manual, caps)
            }
        }
    }

    private fun applyManual(cam: Camera, manual: ManualPhotoSettings, caps: AdvancedPhotoCapabilities) {
        val token = session
        val id = lens?.cameraId ?: return
        if (manual != ManualPhotoSettings()) manualOverrides.add(id)
        applyingManual = true
        render()
        try {
            val future = AdvancedPhotoControls.apply(cam, manual, caps)
            future.addListener({
                if (token == session && !isDestroyed) {
                    applyingManual = false
                    runCatching { future.get() }
                        .onSuccess {
                            if (manual == ManualPhotoSettings()) manualOverrides.remove(id)
                            photoSettings = photoSettings.copy(manual = manual)
                        }
                        .onFailure {
                            photoSettings = photoSettings.copy(manual = ManualPhotoSettings())
                            if (manual == ManualPhotoSettings()) {
                                fail("Falha ao restaurar a câmera · toque para tentar", it)
                            } else {
                                toast("Controles recusados. Voltando ao automático.")
                                rebind()
                            }
                        }
                    render()
                }
            }, main)
        } catch (e: Exception) {
            applyingManual = false
            photoSettings = photoSettings.copy(manual = ManualPhotoSettings())
            toast("Combinação manual indisponível.")
            Log.w(TAG, "Controles manuais: parâmetros recusados", e)
            render()
        }
    }

    private fun toggleHdr() {
        if (hdrRequested) { hdrRequested = false; rebind(); return }
        val choice = lens ?: return
        val helper = hdr ?: HdrPhotoExtension(this, provider ?: return).also { hdr = it }
        val token = session
        helper.query(choice) { result ->
            if (!isDestroyed && token == session && !busy && mode == ShootingMode.PHOTO) {
                if (result.supported) {
                    hdrRequested = true
                    burstDetail = false
                    photoSettings = AdvancedPhotoSettings()
                    rebind()
                } else toast(result.reason ?: "Esta câmera não oferece HDR.")
            }
        }
    }

    private fun lensMenu() {
        val manager = lenses ?: return
        PopupMenu(this, ui.quickControlsButton).apply {
            manager.options.forEachIndexed { i, option ->
                menu.add(0, i + 1, i, option.label).isEnabled = !manager.isRejected(option.key, lensProfile)
            }
            setOnMenuItemClickListener {
                val chosen = manager.options[it.itemId - 1]
                if (locked) { lockLensExplicit = true; lockLensConfigured = true }
                zoomTo(chosen.relativeZoom * chosen.initialCameraZoom, chosen.key)
                true
            }
            show()
        }
    }

    private fun showDiagnostics() {
        val manager = lenses
        val info = manager?.diagnostics(lensProfile)
        val resolution = photo?.capture?.resolutionInfo ?: video?.capture?.resolutionInfo
        val ids = capabilities?.availableLenses?.joinToString("\n") { entry ->
            "ID ${entry.id}: ${entry.physicalIds.sorted().joinToString().ifBlank { "sem IDs físicos anunciados" }}"
        } ?: manager?.options.orEmpty().joinToString("\n") { it.label }
        val report = listOf(
            "Câmera da sessão: ${info?.selectedCameraId ?: "—"}",
            "Saída física solicitada: ${info?.selectedPhysicalCameraId ?: "automática"}",
            "Sensor ativo informado pelo HAL: ${info?.activePhysicalCameraId ?: "não informado"}",
            "Sensor da saída confirmado: ${info?.outputPhysicalCameraId ?: "não informado"}",
            "Zoom confirmado: ${formatZoom(displayZoom)} · solicitado: ${formatZoom(targetZoom)}",
            "Recorte adicional: $extraCrop · escala do visor: ${ui.photoPreview.scaleX}",
            "Resultado AF/AE: ${info?.capture ?: "aguardando"}",
            "Primeira prévia: ${if (firstFrameAt>0) firstFrameAt-bindingStartedAt else null} ms · sessão $session",
            "Buffer: ${resolution?.resolution ?: "—"} · rotação ${resolution?.rotationDegrees ?: "—"}°",
            if (photoMode) "Foto: ${photoSettings.format} · alta resolução: ${photoSettings.highResolution} · HDR: $hdrRequested" else "Configuração da gravação: ${video?.diagnostics()}",
            if (locked) "Horizon Lock: ${sensor.sourceName} · ${video?.effect?.processor?.diagnostics() ?: "aguardando"}" else "",
            ids, info?.rejection?.let { "Configuração recusada: $it" }.orEmpty()
        ).filter { it.isNotBlank() }.joinToString("\n")
        AlertDialog.Builder(this).setTitle("Diagnóstico da câmera").setMessage(report)
            .setPositiveButton("Fechar", null).show()
    }

    private fun formatZoom(value: Float) = String.format(Locale.getDefault(), "%.2f×", value)
    private fun renderZoom() {
        ui.photoPreview.scaleX = dragPreviewCrop ?: extraCrop
        ui.photoPreview.scaleY = ui.photoPreview.scaleX
        ui.zoomReadout.isClickable = softwareStillZoom && !busy
        val requested = targetZoom
        fun label(value: Float): String {
            if (!softwareStillZoom) return formatZoom(value)
            val valueInTenths = (value * 10).roundToInt()
            return if (valueInTenths % 10 == 0) "${valueInTenths / 10}×"
                else String.format(Locale.getDefault(), "%.1f×", valueInTenths / 10f)
        }
        ui.zoomReadout.text = if (zoomInProgress && label(displayZoom) != label(requested))
            "${label(displayZoom)} → ${label(requested)}" else label(displayZoom)
        ui.zoomSlider.isVisible = softwareStillZoom
        ui.zoomSlider.isEnabled = canZoom && !busy
        ViewCompat.setStateDescription(ui.zoomSlider, ui.zoomReadout.text)
        if (softwareStillZoom && !draggingZoom) {
            ui.zoomSlider.progress = PhotoZoomScale.progress(if (zoomInProgress) requested else displayZoom, minimumPhotoZoom())
        }
        ui.recordButton.isEnabled = (recording != null && !stopping) || (ready && failure == null && !busy && !zoomInProgress)
        ui.recordButton.alpha = if (ui.recordButton.isEnabled) 1f else 0.5f
        presets.forEach { (value, button) ->
            val candidate = route(value)
            button.isEnabled = canZoom && !takingPhoto && !awaitingMic && !stopping && candidate != null &&
                ((softwareStillZoom && value >= candidate.displayZoom) ||
                    abs(candidate.displayZoom - value) <= (if (value < 1f) 0.08f else 0.035f * value))
            button.isSelected = ready && handoffStartedAt == null && abs(displayZoom - value) <= (if (value < 1f) 0.08f else 0.035f * value)
        }
    }

    private fun render() {
        ui.root.setPhotoMode(photoMode)
        ui.root.setLockedVideoMode(locked)
        ui.photoPreview.isVisible = !locked
        ui.previewView.isVisible = locked
        ui.modeNight.isSelected = mode == ShootingMode.NIGHT
        ui.modeNight.isEnabled = !busy
        ui.modePro.isSelected = mode == ShootingMode.PRO
        ui.modePro.isEnabled = !busy && photoCaps?.let { it.manual.supportsManualExposure || it.manual.supportsManualFocus || it.manual.awbModes.isNotEmpty() } == true
        ui.modePhoto.isSelected = mode == ShootingMode.PHOTO
        ui.modeVideo.isSelected = !photoMode
        ui.modePhoto.isEnabled = !busy
        ui.modeVideo.isEnabled = !busy
        ui.modeMore.isEnabled = !busy
        ui.qualityButton.isVisible = !photoMode
        ui.fpsButton.isVisible = !photoMode
        ui.lockButton.isVisible = !photoMode
        ui.qualityButton.isEnabled = ready && !busy
        val selectedVideo = video?.diagnostics()
        ui.qualityButton.text = selectedVideo?.selectedQuality?.replace("UHD", "4K")
            ?: if (requestedQuality == Quality.UHD) "4K" else "FHD"
        ui.fpsButton.isEnabled = ready && !busy && (fps == 60 || capabilities?.supportsFps(requestedQuality, 60) == true)
        val cadence = selectedVideo?.configuredFpsRange
        ui.fpsButton.text = when {
            selectedVideo == null -> fps.toString()
            cadence == null -> "Auto"
            cadence.lower == cadence.upper -> cadence.upper.toString()
            else -> "≤${cadence.upper}"
        }
        ui.lockButton.isEnabled = !busy
        ui.lockButton.text = when (horizon.mode()) {
            StabilizationMode.HORIZONTAL_LOCK -> "360°"
            StabilizationMode.STEADY -> if (video?.nativeEis == false) "EIS indisponível" else "EIS"
            StabilizationMode.OFF -> "Off"
        }
        ui.quickControlsButton.isEnabled = !busy
        ui.flashButton.isEnabled = ready && !takingPhoto && camera?.cameraInfo?.hasFlashUnit() == true
        val rec = recording != null
        ui.exposureSlider.isEnabled = !takingPhoto
        ui.galleryThumbnail.isEnabled = !busy
        ui.pauseButton.isVisible = rec
        ui.pauseButton.isEnabled = !stopping
        ui.pauseButton.text = if (paused) "▶" else "Ⅱ"
        ui.pauseButton.contentDescription = if (paused) "Retomar gravação" else "Pausar gravação"
        ui.timerText.isVisible = rec
        ui.recordButton.contentDescription = when { rec -> "Parar gravação"; photoMode -> "Tirar foto"; else -> "Iniciar gravação" }
        val inner = ui.recordInner
        inner.setBackgroundResource(when { rec -> R.drawable.record_inner_active; photoMode -> R.drawable.photo_shutter_idle; else -> R.drawable.record_inner_idle })
        val scale = if (rec) 0.53f else 1f
        inner.animate().scaleX(scale).scaleY(scale).setDuration(130).start()
        renderStatus()
        renderZoom()
    }

    private fun renderStatus() {
        val text = when {
            failure != null -> failure!!
            handoffStartedAt != null -> recoveryMessage ?: if (ui.lensTransitionFrame.isVisible)
                "Trocando lente · quadro anterior" else "Trocando lente…"
            !ready -> recoveryMessage ?: "Abrindo câmera…"
            burst != null -> burstProgress
            takingPhoto -> "Salvando foto…"
            stopping -> "Finalizando MP4…"
            paused -> "Pausado"
            recording != null && locked && !horizon.orientationFresh(SystemClock.elapsedRealtimeNanos()) -> "REC · referência fraca"
            recording != null -> if (audio) "● REC" else "● REC · sem áudio"
            BurstJobs.states.value?.running == true && photoMode -> BurstJobs.states.value!!.detail
            AutoPhotos.states.value?.running == true && photoMode -> AutoPhotos.states.value!!.message
            zoomInProgress -> "Aplicando zoom…"
            autoJpeg && distantFocus -> "Foto · foco distante"
            autoJpeg && focusMessage != null -> focusMessage!!
            mode == ShootingMode.NIGHT -> "Noturno · 5 quadros"
            autoJpeg && extraCrop > 1.001f -> "Foto · zoom digital · IA"
            mode == ShootingMode.PHOTO && burstDetail -> "Foto · fusão de detalhe"
            photoMode -> listOf(if (mode == ShootingMode.PRO) "Pro" else "Foto",
                if (photoSettings.format == PhotoFormat.RAW_DNG) "DNG" else "JPEG",
                if (hdrRequested) "HDR" else if (photoSettings.highResolution) "Alta resolução" else null)
                .filterNotNull().joinToString(" · ")
            locked && !horizon.orientationFresh(SystemClock.elapsedRealtimeNanos()) -> "Buscando horizonte…"
            locked -> "Horizon Lock · 360°"
            horizon.mode() == StabilizationMode.STEADY -> when {
                video?.nativeEis != true -> "Vídeo · EIS indisponível nesta combinação"
                video?.reportedEis == true -> "Vídeo · EIS ativo"
                video?.reportedEis == false -> "Vídeo · HAL informa EIS desligado"
                else -> "Vídeo · EIS solicitado"
            }
            !audio -> "Vídeo · sem áudio"
            else -> "Vídeo"
        }
        if (ui.statusText.text != text) ui.statusText.text = text
    }

    private val telemetry = object : Runnable {
        override fun run() {
            renderStatus()
            val info = lenses?.diagnostics(lensProfile)
            val focus = info?.capture?.let { "${it.afState}/${it.aeState}/${it.focusDistance}" }
            if (focus != lastFocusEvidence && focus != null) {
                Log.i(TAG, "captureState session=$session zoom=$displayZoom $focus")
                lastFocusEvidence = focus
            }
            val actual = info?.outputPhysicalCameraId
            if (actual != physicalLog) { Log.i(TAG, "session=$session activePhysicalId=${actual ?: "unknown"}"); physicalLog = actual }
            ui.root.postDelayed(this, 400)
        }
    }

    private fun syncSensor() {
        if (locked && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) sensor.start()
        else { sensor.stop(); horizon.clearOrientation() }
    }

    override fun onResume() {
        super.onResume()
        power?.addThermalStatusListener(main, thermalListener)
        WindowInsetsControllerCompat(window, ui.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        syncSensor()
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(this, null)
        ui.root.post(telemetry)
        if (hasCamera() && (provider == null || camera == null || failure != null)) {
            recoveryAttempt = 0
            openCamera()
        } else if (hasCamera() && !ready) scheduleRecovery(session, true, -1, 3500L)
    }

    override fun onPause() {
        power?.removeThermalStatusListener(thermalListener)
        ui.root.removeCallbacks(telemetry)
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(this)
        if (recording == null) { sensor.stop(); horizon.clearOrientation() }
        super.onPause()
    }

    override fun onStop() {
        burst?.cancel()
        if (recording != null) stopVideo() else horizon.clearOrientation()
        sensor.stop()
        if (!softwareStillZoom && lensSwitch != null) { targetZoom = displayZoom; zoomPending = false }
        cancelLensSwitch()
        cancelPhotoClose?.invoke()
        photoZoom.finishGesture(photoZoom.intent.generation)
        draggingZoom = false
        sliderGeneration = null
        finishPhotoHandoff()
        reopen?.let(ui.root::removeCallbacks)
        openingTimeout?.let(ui.root::removeCallbacks)
        physicalVerification?.let(ui.root::removeCallbacks)
        physicalVerification = null
        reopen = null
        openingTimeout = null
        super.onStop()
    }

    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit
    override fun onDisplayChanged(displayId: Int) {
        if (!locked && !busy && camera != null && ui.root.display?.rotation != boundRotation) rebind()
    }

    override fun onDestroy() {
        burst?.cancel()
        hdr?.close()
        sensor.release()
        recording?.stop()
        releaseSession()
        library.close()
        super.onDestroy()
    }

    private fun fail(message: String, error: Throwable) {
        Log.e(TAG, message, error)
        failure = message
        ready = false
        finishPhotoHandoff()
        render()
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun haptic() { ui.root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }

    companion object { private const val TAG = "HorizonCam" }
}
