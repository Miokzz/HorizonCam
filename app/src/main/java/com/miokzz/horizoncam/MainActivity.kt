package com.miokzz.horizoncam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.camera2.CameraCharacteristics
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
import com.miokzz.horizoncam.camera.RearLensManager
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
import com.miokzz.horizoncam.stabilization.AngleMath
import com.miokzz.horizoncam.video.VideoEngine
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

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
    private var extraCrop = 1f
    private var zoomCommand = 0
    private var zoomPending = false
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
        BurstJobs.states.observe(this) { state ->
            if (!state.running && state.detail.isNotEmpty()) {
                val uri = state.output ?: state.reference
                if (uri != null && prefs.getString("burstSeen", null) != uri.toString()) {
                    remember(uri, state.detail, burstZoom)
                    prefs.edit().putString("burstSeen", uri.toString()).apply()
                } else if (uri == null) toast(state.detail)
            }
            renderStatus()
        }
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
        render()
        if (hasCamera()) openCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun hasCamera() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun retry() {
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
        displayZoom /= extraCrop
        extraCrop = 1f
        mode = next
        burstDetail = false
        hdrRequested = false
        photoSettings = AdvancedPhotoSettings()
        prefs.edit().putString("shootingMode", next.name).apply()
        haptic()
        syncSensor()
        rebind()
    }

    private fun releaseSession() {
        session++
        zoomPending = false
        zoomCommand++
        applyingManual = false
        camera?.cameraInfo?.apply {
            cameraState.removeObservers(this@MainActivity)
            if (lens?.isPhysicalOnly != true) zoomState.removeObservers(this@MainActivity)
            torchState.removeObservers(this@MainActivity)
        }
        ready = false
        lenses?.invalidateCaptureDiagnostics()
        provider?.unbindAll()
        video?.close()
        video = null
        photo = null
        camera = null
        ui.exposureSlider.isVisible = false
        ui.focusIndicator.isVisible = false
        physicalLog = null
    }

    private fun rebind(allowEis: Boolean = true) {
        if (busy || isDestroyed || isFinishing) return
        releaseSession()
        failure = null
        render()
        val owner = provider ?: return
        val token = session
        try {
            val manager = lenses ?: RearLensManager.discover(this, owner).also { lenses = it }
            if (lens == null && locked) {
                val primary = manager.defaultOption
                val wide = manager.route(0.6f, primary.key, primary.minCameraZoom, primary.maxCameraZoom, false)
                if (abs(wide.displayZoom - 0.6f) <= 0.08f) {
                    lens = manager.option(wide.targetKey)
                    displayZoom = wide.displayZoom
                }
            }
            val choice = lens ?: manager.defaultOption
            lens = choice
            val builder = Preview.Builder()
            manager.attachCaptureDiagnostics(builder, choice)
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
                val engine = PhotoEngine(boundRotation, portrait, builder, ui.photoPreview.surfaceProvider, photoSettings, photoCaps, hdrRequested)
                photo = engine
                owner.bindToLifecycle(this, captureSelector, engine.group)
            } else {
                if (horizon.mode() == StabilizationMode.STEADY && (!eisSupported || !allowEis)) {
                    horizon.setMode(StabilizationMode.OFF)
                    toast("Estabilização nativa indisponível nesta combinação.")
                }
                val rotation = if (locked) Surface.ROTATION_90 else boundRotation
                val aspect = if (locked || !portrait) Rational(16, 9) else Rational(9, 16)
                val surface = if (locked) ui.previewView else ui.photoPreview.surfaceProvider
                val eis = allowEis && eisSupported && horizon.mode() == StabilizationMode.STEADY && fps == 30 && requestedQuality == Quality.FHD
                val engine = VideoEngine(horizon, builder, surface, rotation, aspect, requestedQuality, fps, eis, info) { error ->
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
            if (!choice.isPhysicalOnly) {
                val state = bound.cameraInfo.zoomState.value
                val initial = (displayZoom / choice.relativeZoom).coerceIn(state?.minZoomRatio ?: choice.minCameraZoom, state?.maxZoomRatio ?: choice.maxCameraZoom)
                extraCrop = if (autoJpeg) StillFraming.digitalRatio(displayZoom, initial * choice.relativeZoom) else 1f
                if (initial != 1f) applyCameraZoom(bound, initial)
                bound.cameraInfo.zoomState.observe(this) { zoom ->
                    if (token == session && zoom != null) {
                        displayZoom = (zoom.zoomRatio * choice.relativeZoom * extraCrop).coerceAtMost(if (autoJpeg) 30f else Float.MAX_VALUE)
                        renderZoom()
                    }
                }
            } else {
                extraCrop = if (autoJpeg) StillFraming.digitalRatio(displayZoom, choice.relativeZoom) else 1f
                displayZoom = choice.relativeZoom * extraCrop
            }
            bound.cameraInfo.torchState.observe(this) { if (session == token) ui.flashButton.isSelected = it == 1 }
            bound.cameraInfo.cameraState.observe(this) { state ->
                if (session != token) return@observe
                val code = state.error?.code
                ready = state.type == CameraState.Type.OPEN && code == null
                if (code != null) {
                    failure = "Câmera indisponível · $code"
                    Log.w(TAG, "session=$token cameraError=$code", state.error?.cause)
                    if (code == CameraState.ERROR_STREAM_CONFIG && !busy) ui.root.post {
                        if (session == token) fallback(allowEis, IllegalStateException("Configuração recusada ($code)"))
                    }
                } else if (ready) failure = null
                render()
            }
        } catch (error: Exception) { fallback(allowEis, error) }
    }

    private fun fallback(allowEis: Boolean, error: Throwable) {
        val manager = lenses
        val choice = lens
        Log.w(TAG, "Configuração recusada ${choice?.key}", error)
        when {
            photoMode && (photoSettings != AdvancedPhotoSettings() || hdrRequested) -> {
                if (hdrRequested && choice != null) hdr?.onBindRejected(choice, error.message ?: "Sessão HDR recusada")
                photoSettings = AdvancedPhotoSettings()
                hdrRequested = false
                toast("Esta combinação de foto foi recusada. Voltando ao JPEG automático.")
                rebind()
            }
            manager != null && choice != null && choice != manager.defaultOption -> {
                manager.onBindRejected(choice, error.message ?: "configuração")
                lens = manager.defaultOption
                toast("A lente recusou este modo. Usando a câmera automática.")
                rebind()
            }
            !photoMode && allowEis && horizon.mode() == StabilizationMode.STEADY -> rebind(false)
            !photoMode && fps != 30 -> { fps = 30; toast("Gravação disponível a 30 fps."); rebind() }
            !photoMode && requestedQuality == Quality.UHD -> {
                requestedQuality = Quality.FHD; toast("4K indisponível nesta combinação."); rebind()
            }
            else -> { releaseSession(); fail("Falha de câmera · toque para tentar", error) }
        }
    }

    private fun route(requested: Float, preferred: String? = null): ZoomRoute? {
        val choice = lens ?: return null
        val state = if (choice.isPhysicalOnly) null else camera?.cameraInfo?.zoomState?.value
        return lenses?.route(requested, choice.key, state?.minZoomRatio ?: choice.minCameraZoom,
            state?.maxZoomRatio ?: choice.maxCameraZoom, recording != null, preferred, stillPhoto = autoJpeg && !hdrRequested)
    }

    private fun zoomTo(requested: Float, preferred: String? = null) {
        if (!ready || takingPhoto || awaitingMic || stopping) return
        val desired = if (autoJpeg && preferred == null) requested.coerceIn(0.6f, StillFraming.MAX_ZOOM) else requested
        val route = route(desired, preferred) ?: return
        val crop = if (autoJpeg && preferred == null)
            StillFraming.digitalRatio(desired, route.displayZoom) else 1f
        extraCrop = crop
        displayZoom = route.displayZoom * crop
        if (route.requiresRebind) {
            if (busy) return
            lens = lenses?.option(route.targetKey) ?: return
            rebind()
        } else if (lens?.isPhysicalOnly != true) {
            camera?.let { applyCameraZoom(it, route.cameraZoomRatio) }
            renderZoom()
        } else renderZoom()
    }

    private fun applyCameraZoom(cam: Camera, ratio: Float) {
        val ticket = ++zoomCommand
        zoomPending = true
        val applied = cam.cameraControl.setZoomRatio(ratio)
        if (autoJpeg) ui.recordButton.isEnabled = false
        applied.addListener({
            if (ticket != zoomCommand || isDestroyed) return@addListener
            zoomPending = false
            runCatching { applied.get() }.onFailure {
                extraCrop = 1f
                displayZoom = (cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f) * (lens?.relativeZoom ?: 1f)
                toast("A câmera recusou a ampliação solicitada.")
            }
            render()
        }, main)
    }

    private fun choosePhotoZoom() {
        if (!autoJpeg || busy || !ready) return
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
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { pinchZoom = displayZoom; return ready }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinchZoom = (pinchZoom * detector.scaleFactor).coerceIn(0.05f, 1000f)
                if (autoJpeg) pinchZoom = pinchZoom.coerceAtMost(StillFraming.MAX_ZOOM)
                zoomTo(pinchZoom)
                return true
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
        if (takingPhoto) return
        val cam = camera ?: return
        val w = ui.previewContainer.width.coerceAtLeast(1).toFloat()
        val h = ui.previewContainer.height.coerceAtLeast(1).toFloat()
        val manualFocus = mode == ShootingMode.PRO && photoSettings.manual.focusDistanceDiopters != null
        val manualExposure = mode == ShootingMode.PRO && photoSettings.manual.iso != null
        val point = if (!locked) ui.photoPreview.meteringPointFactory.createPoint(x, y) else {
            val g = horizon.frameGeometry(SystemClock.elapsedRealtimeNanos())
            val inv = AngleMath.correctionMatrix(g.rotationDegrees.toDouble(), g.cropScale.toDouble(), 16.0 / 9).inverse()
            val p = inv.apply(2.0 * x / w - 1, 1 - 2.0 * y / h)
            SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(
                ((1 + p.first) / 2).toFloat().coerceIn(0f, 1f), ((1 - p.second) / 2).toFloat().coerceIn(0f, 1f))
        }
        val flags = (if (manualFocus) 0 else FocusMeteringAction.FLAG_AF) or (if (manualExposure) 0 else FocusMeteringAction.FLAG_AE)
        if (flags == 0) return
        val action = FocusMeteringAction.Builder(point, flags)
            .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        if (cam.cameraInfo.isFocusMeteringSupported(action)) observeControl(cam.cameraControl.startFocusAndMetering(action), "Foco")
        ui.focusIndicator.apply {
            translationX = x - w / 2
            translationY = y - h / 2
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
        if (!ready || busy || failure != null || (autoJpeg && zoomPending)) return
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
                val trace = lenses?.diagnostics()
                Log.i(TAG, "Disparo zoom=$displayZoom recorte=$extraCrop camera=${trace?.selectedCameraId} fisicaSolicitada=${trace?.selectedPhysicalCameraId} ativaHAL=${trace?.activePhysicalCameraId}")
                AutoPhotos.capture(applicationContext, engine.capture, displayZoom, extraCrop, main) { error ->
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
        val sequence = BurstCapture(cacheDir, cam, capture, main, mode == ShootingMode.NIGHT,
            { lenses?.diagnostics()?.activePhysicalCameraId },
            { completed, total -> burstProgress = "Capturando · $completed/$total"; renderStatus() },
            { result ->
                burst = null
                takingPhoto = false
                result.onSuccess { frames -> BurstJobs.process(applicationContext, frames, burstDetail) }
                    .onFailure { error ->
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
                            is VideoRecordEvent.Start -> render()
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
            menu.add(0, 2, 1, "Ultra HD · 4K").isEnabled = cap.uhd && horizon.mode() != StabilizationMode.STEADY
            setOnMenuItemClickListener {
                requestedQuality = if (it.itemId == 2) Quality.UHD else Quality.FHD
                if (!cap.supportsFps(requestedQuality, fps)) fps = 30
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
                horizon.setMode(mode)
                prefs.edit().putString("stabilization", mode.name).apply()
                if (mode == StabilizationMode.STEADY) { fps = 30; requestedQuality = Quality.FHD }
                syncSensor()
                rebind()
                haptic()
                true
            }
            show()
        }
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
                menu.add(0, i + 1, i, option.label).isEnabled = !manager.isRejected(option.key)
            }
            setOnMenuItemClickListener {
                val chosen = manager.options[it.itemId - 1]
                zoomTo(chosen.relativeZoom * chosen.initialCameraZoom, chosen.key)
                true
            }
            show()
        }
    }

    private fun showDiagnostics() {
        val manager = lenses
        val info = manager?.diagnostics()
        val resolution = photo?.capture?.resolutionInfo ?: video?.capture?.resolutionInfo
        val ids = capabilities?.availableLenses?.joinToString("\n") { entry ->
            "ID ${entry.id}: ${entry.physicalIds.sorted().joinToString().ifBlank { "sem IDs físicos anunciados" }}"
        } ?: manager?.options.orEmpty().joinToString("\n") { it.label }
        val report = listOf(
            "Câmera da sessão: ${info?.selectedCameraId ?: "—"}",
            "Saída física solicitada: ${info?.selectedPhysicalCameraId ?: "automática"}",
            "Sensor ativo informado pelo HAL: ${info?.activePhysicalCameraId ?: "não informado"}",
            "Zoom relativo à principal: ${formatZoom(displayZoom)}",
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
        ui.photoPreview.scaleX = extraCrop
        ui.photoPreview.scaleY = extraCrop
        ui.zoomReadout.isClickable = autoJpeg && !busy
        ui.zoomReadout.text = formatZoom(displayZoom)
        presets.forEach { (value, button) ->
            val candidate = route(value)
            button.isEnabled = ready && !takingPhoto && !awaitingMic && !stopping && candidate != null &&
                ((autoJpeg && value >= candidate.displayZoom) ||
                    abs(candidate.displayZoom - value) <= (if (value < 1f) 0.08f else 0.035f * value))
            button.isSelected = abs(displayZoom - value) <= (if (value < 1f) 0.08f else 0.035f * value)
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
        ui.fpsButton.isEnabled = ready && !busy && (fps == 60 || (capabilities?.supportsFps(requestedQuality, 60) == true && horizon.mode() != StabilizationMode.STEADY))
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
            StabilizationMode.STEADY -> "EIS"
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
        ui.recordButton.isEnabled = (rec && !stopping) || (ready && failure == null && !busy && (!autoJpeg || !zoomPending))
        ui.recordButton.alpha = if (ui.recordButton.isEnabled) 1f else 0.5f
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
            !ready -> "Abrindo câmera…"
            burst != null -> burstProgress
            takingPhoto -> "Salvando foto…"
            stopping -> "Finalizando MP4…"
            paused -> "Pausado"
            recording != null && locked && !horizon.orientationFresh(SystemClock.elapsedRealtimeNanos()) -> "REC · referência fraca"
            recording != null -> if (audio) "● REC" else "● REC · sem áudio"
            BurstJobs.states.value?.running == true && photoMode -> BurstJobs.states.value!!.detail
            AutoPhotos.states.value?.running == true && photoMode -> AutoPhotos.states.value!!.message
            mode == ShootingMode.NIGHT -> "Noturno · 5 quadros"
            autoJpeg && extraCrop > 1.001f -> "Foto · zoom digital · IA"
            mode == ShootingMode.PHOTO && burstDetail -> "Foto · fusão de detalhe"
            photoMode -> listOf(if (mode == ShootingMode.PRO) "Pro" else "Foto",
                if (photoSettings.format == PhotoFormat.RAW_DNG) "DNG" else "JPEG",
                if (hdrRequested) "HDR" else if (photoSettings.highResolution) "Alta resolução" else null)
                .filterNotNull().joinToString(" · ")
            locked && !horizon.orientationFresh(SystemClock.elapsedRealtimeNanos()) -> "Buscando horizonte…"
            locked -> "Horizon Lock · 360°"
            !audio -> "Vídeo · sem áudio"
            horizon.mode() == StabilizationMode.STEADY -> "Vídeo · EIS"
            else -> "Vídeo"
        }
        if (ui.statusText.text != text) ui.statusText.text = text
    }

    private val telemetry = object : Runnable {
        override fun run() {
            renderStatus()
            val actual = lenses?.diagnostics()?.activePhysicalCameraId
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
        if (hasCamera() && provider == null) openCamera()
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
        render()
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun haptic() { ui.root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }

    companion object { private const val TAG = "HorizonCam" }
}
