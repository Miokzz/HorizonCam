package com.miokzz.horizoncam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.net.Uri
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.widget.PopupMenu
import android.widget.SeekBar
import android.view.View
import android.view.GestureDetector
import android.view.MotionEvent
import android.util.Size
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import com.miokzz.horizoncam.camera.CameraCapabilities
import com.miokzz.horizoncam.camera.PublicLensDiscovery
import com.miokzz.horizoncam.stabilization.AngleMath
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Gravity
import android.view.Surface
import android.widget.FrameLayout
import android.provider.MediaStore
import android.util.Range
import android.util.Rational
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.ViewPort
import android.view.ScaleGestureDetector
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.common.util.concurrent.ListenableFuture
import com.miokzz.horizoncam.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var gravitySensor: GravityRollSensor
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private val horizonState = HorizonState()
    private var horizonEffect: HorizonGlEffect? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var previewUseCase: Preview? = null
    private var recording: Recording? = null

    private var requestedQuality = Quality.FHD
    private var requestedFps = 30
    private var cameraStarting = false
    private var nativeEisActive = false
    private var eisSupported = false
    private var capabilities: CameraCapabilities? = null
    private var wideLens: PublicLensDiscovery.WideLens? = null
    private var lensProbeCompleted = false
    private var usingWide = false
    private var torchEnabled = false
    private var paused = false
    private var lastVideoUri: Uri? = null
    private val galleryExecutor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("horizoncam", MODE_PRIVATE) }
    private var soundEnabled = true
    private lateinit var tapDetector: GestureDetector

    private var showDiagnostics = false
    private var lastDiagnosticsAt = 0L
    private var selectedZoom = 1f

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cameraGranted = result[Manifest.permission.CAMERA] == true
        val audioGranted = result[Manifest.permission.RECORD_AUDIO] == true
        if (cameraGranted && audioGranted) startCamera()
        else Toast.makeText(this, getString(R.string.need_permissions), Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enterImmersiveMode()

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        updateControlsForOrientation()
        binding.statusText.setOnLongClickListener {
            showDiagnostics = !showDiagnostics
            updateStatusText()
            true
        }
        binding.qualityButton.text = "FHD"
        binding.fpsButton.text = "30"
        binding.statusText.text = "H LOCK"

        soundEnabled = prefs.getBoolean("sound", true)
        binding.gridView.visibility = if (prefs.getBoolean("grid", false)) View.VISIBLE else View.GONE
        lastVideoUri = prefs.getString("lastVideo", null)?.let(Uri::parse)
        lastVideoUri?.let(::showLastVideo)

        gravitySensor = GravityRollSensor(this) { reading ->
            horizonState.pushRoll(reading.timestampNs, reading.rollDegrees.toFloat())
            val time = android.os.SystemClock.uptimeMillis()
            if (time - lastDiagnosticsAt > 240L) {
                lastDiagnosticsAt = time
                runOnUiThread { updateStatusText() }
            }
        }

        scaleGestureDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return false
                    val zoomState = cam.cameraInfo.zoomState.value ?: return false
                    val target = (zoomState.zoomRatio * detector.scaleFactor)
                        .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                    cam.cameraControl.setZoomRatio(target)
                    selectedZoom = if (usingWide) target * 0.6f else target
                    updateZoomUi(selectedZoom)
                    return true
                }
            }
        )

        tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true
            override fun onSingleTapUp(event: MotionEvent): Boolean {
                if (!scaleGestureDetector.isInProgress) focusAt(event.x, event.y)
                return true
            }
        })
        binding.previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            tapDetector.onTouchEvent(event)
            true
        }

        binding.exposureSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val exposure = camera?.cameraInfo?.exposureState ?: return
                val index = exposure.exposureCompensationRange.lower + progress
                camera?.cameraControl?.setExposureCompensationIndex(index)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) {
                binding.exposureSlider.postDelayed({
                    binding.exposureSlider.visibility = View.GONE
                }, 2500)
            }
        })

        binding.lockButton.setOnClickListener { showStabilizationOptions() }
        binding.lockButton.setOnLongClickListener {
            // Diagnostics only. The app never asks the user to calibrate.
            showDiagnostics = !showDiagnostics
            updateStatusText()
            true
        }
        binding.quickControlsButton.setOnClickListener { showQuickControls() }
        binding.flashButton.setOnClickListener { toggleTorch() }
        binding.galleryThumbnail.setOnClickListener { openLastVideo() }
        binding.pauseButton.setOnClickListener { togglePause() }

        binding.qualityButton.setOnClickListener {
            if (recording != null) return@setOnClickListener
            val next = if (requestedQuality == Quality.UHD) Quality.FHD else Quality.UHD
            if (capabilities?.hasQuality(next) == false) {
                toast("Resolução indisponível nesta câmera.")
                return@setOnClickListener
            }
            if (horizonState.mode() == StabilizationMode.STEADY &&
                next == Quality.UHD) {
                toast("Super Steady disponível somente em FHD/30 neste pipeline.")
                return@setOnClickListener
            }
            requestedQuality = next
            if (requestedQuality == Quality.UHD && requestedFps == 60) {
                requestedFps = 30
                binding.fpsButton.text = "30"
            }
            binding.qualityButton.text = if (requestedQuality == Quality.UHD) "UHD" else "FHD"
            restartCamera()
        }

        binding.fpsButton.setOnClickListener {
            if (recording != null) return@setOnClickListener
            if (requestedFps == 30 &&
                (capabilities?.fps60 == false ||
                    requestedQuality == Quality.UHD ||
                    horizonState.mode() == StabilizationMode.STEADY)) {
                toast("60 FPS não foi validado nesta combinação.")
                return@setOnClickListener
            }
            requestedFps = if (requestedFps == 30) 60 else 30
            binding.fpsButton.text = requestedFps.toString()
            restartCamera()
        }

        binding.recordButton.setOnClickListener {
            if (recording == null) startRecording() else stopRecording()
        }

        binding.zoom06Button.setOnClickListener { setZoom(0.6f) }
        binding.zoom1Button.setOnClickListener { setZoom(1f) }
        binding.zoom2Button.setOnClickListener { setZoom(2f) }
        binding.zoom3Button.setOnClickListener { setZoom(3f) }

        updateZoomUi(1f)
        requestPermissionsOrStart()
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        gravitySensor.start()
    }

    override fun onPause() {
        gravitySensor.stop()
        super.onPause()
    }

    override fun onDestroy() {
        gravitySensor.release()
        recording?.stop()
        recording = null
        cameraProvider?.unbindAll()
        horizonEffect?.close()
        horizonEffect = null
        galleryExecutor.shutdown()
        super.onDestroy()
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun requestPermissionsOrStart() {
        val cameraOk = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val audioOk = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (cameraOk && audioOk) {
            startCamera()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun startCamera() {
        if (cameraStarting || isFinishing || isDestroyed) return
        cameraStarting = true
        binding.statusText.text = "STARTING"

        val providerFuture: ListenableFuture<ProcessCameraProvider> =
            ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                bindCameraWithFallback(requestedFps)
            } catch (t: Throwable) {
                cameraStarting = false
                binding.statusText.text = "CAMERA ERROR"
                Toast.makeText(this, "Falha ao abrir câmera: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun restartCamera() {
        previewUseCase = null
        cameraProvider?.unbindAll()
        horizonEffect?.close()
        horizonEffect = null
        cameraStarting = false
        startCamera()
    }

    private fun bindCameraWithFallback(fps: Int, allowEis: Boolean? = null) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        if (!lensProbeCompleted) {
            wideLens = runCatching {
                PublicLensDiscovery.findAccessibleUltrawide(this, provider)
            }.getOrNull()
            lensProbeCompleted = true
            if (horizonState.isEnabled() && wideLens != null) {
                usingWide = true
                selectedZoom = 0.6f
            }
        }
        val selector = if (usingWide) wideLens?.selector
            ?: CameraSelector.DEFAULT_BACK_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA

        val halCanStabilize = runCatching {
            val info = provider.getCameraInfo(selector)
            Preview.getPreviewCapabilities(info).isStabilizationSupported &&
                Recorder.getVideoCapabilities(info).isStabilizationSupported
        }.getOrDefault(false)
        eisSupported = halCanStabilize
        val eis = allowEis ?: (
            halCanStabilize &&
            horizonState.mode() != StabilizationMode.OFF &&
            requestedQuality == Quality.FHD && fps == 30
        )

        val outputRotation = if (horizonState.isEnabled()) {
            Surface.ROTATION_90
        } else {
            binding.previewView.display?.rotation ?: Surface.ROTATION_0
        }
        val preview = Preview.Builder()
            .setTargetRotation(outputRotation)
            .apply { if (eis) setPreviewStabilizationEnabled(true) }
            .build()
            .also {
                it.setSurfaceProvider(ContextCompat.getMainExecutor(this), binding.previewView)
            }

        val qualitySelector = QualitySelector.from(
            requestedQuality,
            FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)
        )

        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()

        val capture = VideoCapture.Builder(recorder)
            .setTargetRotation(outputRotation)
            .setTargetFrameRate(Range(fps, fps))
            .apply { if (eis) setVideoStabilizationEnabled(true) }
            .build()

        val effect = HorizonGlEffect.create(horizonState) { error ->
            runOnUiThread {
                binding.statusText.text = "OPENGL ERROR"
                Toast.makeText(
                    this,
                    "Horizon OpenGL: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // A shared CameraX crop/viewport is essential: PREVIEW and VIDEO
        // must receive the same optics, then the GL shader uses one matrix.
        val viewPort = ViewPort.Builder(Rational(16, 9), outputRotation)
            .setScaleType(ViewPort.FILL_CENTER)
            .build()
        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(capture)
            .setViewPort(viewPort)
            .addEffect(effect)
            .build()

        try {
            horizonEffect?.close()
            horizonEffect = effect
            previewUseCase = preview
            videoCapture = capture
            camera = provider.bindToLifecycle(this, selector, group)
            val source = runCatching {
                Camera2CameraInfo.from(camera!!.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            }.getOrNull()
            horizonState.setCameraRealtimeTimestamp(
                source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            )
            nativeEisActive = eis
            cameraStarting = false
            capabilities = runCatching {
                CameraCapabilities.inspect(this, camera!!.cameraInfo)
            }.getOrNull()
            binding.flashButton.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
            binding.flashButton.alpha = if (binding.flashButton.isEnabled) 1f else 0.35f
            if (torchEnabled) camera?.cameraControl?.enableTorch(true)
            val zoomRange = camera?.cameraInfo?.zoomState?.value
            if (zoomRange != null) {
                val opticalZoom = if (usingWide) 1f
                    else selectedZoom.coerceIn(zoomRange.minZoomRatio, zoomRange.maxZoomRatio)
                camera?.cameraControl?.setZoomRatio(opticalZoom)
                selectedZoom = if (usingWide) 0.6f else opticalZoom
            }
            updateStatusText()
            updateZoomAvailability()
            updateZoomUi(selectedZoom)
        } catch (t: Throwable) {
            effect.close()

            if (usingWide) {
                // A physically accessible camera may still reject an encoder
                // or GPU/VideoCapture combination. Never strand the preview.
                usingWide = false
                selectedZoom = 1f
                toast("Ultra-wide não aceitou este modo. Usando a câmera principal.")
                bindCameraWithFallback(fps)
            } else if (eis) {
                // Some devices report HAL stabilization but cannot combine it
                // with an external GPU effect. Rebind without HAL EIS.
                nativeEisActive = false
                if (horizonState.mode() == StabilizationMode.STEADY) {
                    horizonState.setMode(StabilizationMode.OFF)
                    binding.lockButton.text = "OFF"
                    toast("Estabilização nativa indisponível nesta combinação.")
                }
                bindCameraWithFallback(fps, false)
            } else if (fps == 60) {
                requestedFps = 30
                binding.fpsButton.text = "30"
                Toast.makeText(
                    this,
                    "60 fps indisponível nesta combinação. Usando 30.",
                    Toast.LENGTH_SHORT
                ).show()
                bindCameraWithFallback(30)
            } else {
                cameraStarting = false
                binding.statusText.text = "BIND ERROR"
                Toast.makeText(
                    this,
                    "Combinação não suportada: ${t.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setZoom(requested: Float) {
        val cam = camera ?: return
        val zoomState = cam.cameraInfo.zoomState.value ?: return

        // Optical lens changes require a new CameraX session unless both
        // physical cameras are exposed as one logical zoom stream.
        if (requested <= 0.61f && wideLens != null && !usingWide) {
            if (recording != null) {
                toast("Não é possível trocar a lente física durante REC.")
                return
            }
            usingWide = true
            selectedZoom = 0.6f
            restartCamera()
            return
        }
        if (requested >= 0.95f && usingWide) {
            if (recording != null) {
                toast("Não é possível trocar a lente física durante REC.")
                return
            }
            usingWide = false
            selectedZoom = requested
            restartCamera()
            return
        }

        val actual = if (usingWide) {
            (requested / 0.6f).coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        } else {
            requested.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        }
        cam.cameraControl.setZoomRatio(actual)
        selectedZoom = if (usingWide) actual * 0.6f else actual
        updateZoomUi(selectedZoom)

        if (!usingWide && requested < zoomState.minZoomRatio - 0.01f) {
            toast("A lente ultra-wide não é acessível pela API pública.")
        }
    }

    private fun updateZoomAvailability() {
        val state = camera?.cameraInfo?.zoomState?.value ?: return
        val supportsUltra = wideLens != null ||
            (!usingWide && state.minZoomRatio <= 0.61f)
        binding.zoom06Button.isEnabled = supportsUltra
        binding.zoom06Button.alpha = if (supportsUltra) 1f else 0.35f
        binding.zoom2Button.isEnabled = usingWide || state.maxZoomRatio >= 2f
        binding.zoom2Button.alpha = if (binding.zoom2Button.isEnabled) 1f else 0.35f
        binding.zoom3Button.isEnabled = usingWide || state.maxZoomRatio >= 3f
        binding.zoom3Button.alpha = if (binding.zoom3Button.isEnabled) 1f else 0.35f
    }

    private fun updateZoomUi(zoom: Float) {
        val buttons = listOf(
            0.6f to binding.zoom06Button,
            1f to binding.zoom1Button,
            2f to binding.zoom2Button,
            3f to binding.zoom3Button
        )
        buttons.forEach { (value, button) ->
            val selected = abs(zoom - value) < 0.22f
            button.backgroundTintList = ColorStateList.valueOf(
                if (selected) Color.WHITE else Color.argb(150, 0, 0, 0)
            )
            button.setTextColor(if (selected) Color.BLACK else Color.WHITE)
        }
    }

    private fun startRecording() {
        val capture = videoCapture ?: return
        // Unlike the Samsung tutorial's recommended starting grip, this
        // implementation permits REC in either pose. H Lock always encodes
        // a consistent landscape output and never calibrates on REC.
        horizonState.startRecording()
        paused = false
        binding.pauseButton.text = "Ⅱ"

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "HorizonCam_$stamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/HorizonCam")
        }

        val options = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        val pending = capture.output.prepareRecording(this, options)
        val withAudio =
            if (soundEnabled &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                pending.withAudioEnabled()
            } else {
                pending
            }

        setSettingsEnabled(false)
        setRecordingUi(true)

        recording = withAudio.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    binding.timerText.visibility = android.view.View.VISIBLE
                    binding.timerText.text = "00:00"
                }

                is VideoRecordEvent.Pause -> {
                    paused = true
                    binding.pauseButton.text = "▶"
                }

                is VideoRecordEvent.Resume -> {
                    paused = false
                    binding.pauseButton.text = "Ⅱ"
                }

                is VideoRecordEvent.Status -> {
                    val seconds =
                        event.recordingStats.recordedDurationNanos / 1_000_000_000L
                    binding.timerText.text =
                        String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
                }

                is VideoRecordEvent.Finalize -> {
                    recording?.close()
                    recording = null
                    horizonState.stopRecording()
                    setSettingsEnabled(true)
                    setRecordingUi(false)

                    if (event.hasError()) {
                        Toast.makeText(
                            this,
                            "Erro ao gravar: ${event.error}",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        val saved = event.outputResults.outputUri
                        verifySavedVideo(saved)
                        toast("Vídeo salvo em Movies/HorizonCam")
                    }
                }
            }
        }
    }

    private fun stopRecording() {
        recording?.stop()
    }

    private fun setRecordingUi(isRecording: Boolean) {
        binding.recordInner.setBackgroundResource(
            if (isRecording) R.drawable.record_inner_active
            else R.drawable.record_inner_idle
        )

        val sizeDp = if (isRecording) 28 else 54
        val lp = binding.recordInner.layoutParams
        lp.width = dp(sizeDp)
        lp.height = dp(sizeDp)
        binding.recordInner.layoutParams = lp

        binding.timerText.visibility =
            if (isRecording) android.view.View.VISIBLE else android.view.View.GONE
        binding.pauseButton.visibility =
            if (isRecording) View.VISIBLE else View.GONE
        binding.recordButton.contentDescription =
            if (isRecording) "Parar gravação" else "Iniciar gravação"
        updateStatusText()
    }

    private fun setSettingsEnabled(enabled: Boolean) {
        binding.qualityButton.isEnabled = enabled
        binding.fpsButton.isEnabled = enabled
        binding.lockButton.isEnabled = enabled
        binding.quickControlsButton.isEnabled = enabled
    }

    private fun pulseLockButton() {
        binding.lockButton.animate()
            .scaleX(1.12f)
            .scaleY(1.12f)
            .setDuration(90)
            .withEndAction {
                binding.lockButton.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120)
                    .start()
            }
            .start()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateControlsForOrientation()

        // Never rebind while rotating the phone. For ordinary video modes,
        // the metadata follows screen orientation when not recording.
        // Horizontal Lock uses an absolute landscape reference instead.
        if (recording == null && horizonState.mode() != StabilizationMode.HORIZONTAL_LOCK) {
            // Normal video follows display rotation. ViewPort cannot rotate
            // after binding, so it must be rebuilt in conventional modes.
            restartCamera()
        }
    }

    private fun updateControlsForOrientation() {
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        val record = binding.recordButton.layoutParams as FrameLayout.LayoutParams
        record.gravity = if (portrait) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                         else Gravity.END or Gravity.CENTER_VERTICAL
        record.bottomMargin = if (portrait) dp(64) else 0
        record.marginEnd = if (portrait) 0 else dp(26)
        binding.recordButton.layoutParams = record

        val zoom = binding.zoomControls.layoutParams as FrameLayout.LayoutParams
        zoom.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        zoom.bottomMargin = if (portrait) dp(165) else dp(22)
        binding.zoomControls.layoutParams = zoom

        val mode = binding.modeLabel.layoutParams as FrameLayout.LayoutParams
        mode.gravity = if (portrait) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                       else Gravity.END or Gravity.CENTER_VERTICAL
        mode.marginEnd = if (portrait) 0 else dp(124)
        mode.bottomMargin = if (portrait) dp(155) else 0
        binding.modeLabel.layoutParams = mode

        val status = binding.statusText.layoutParams as FrameLayout.LayoutParams
        status.topMargin = if (portrait) dp(74) else dp(18)
        binding.statusText.layoutParams = status

        val thumb = binding.galleryThumbnail.layoutParams as FrameLayout.LayoutParams
        thumb.gravity = if (portrait) Gravity.BOTTOM or Gravity.START
                        else Gravity.START or Gravity.CENTER_VERTICAL
        thumb.marginStart = dp(18)
        thumb.bottomMargin = if (portrait) dp(82) else 0
        binding.galleryThumbnail.layoutParams = thumb

        val pause = binding.pauseButton.layoutParams as FrameLayout.LayoutParams
        pause.gravity = if (portrait) Gravity.BOTTOM or Gravity.END
                        else Gravity.END or Gravity.CENTER_VERTICAL
        pause.marginEnd = if (portrait) dp(40) else dp(129)
        pause.bottomMargin = if (portrait) dp(78) else 0
        binding.pauseButton.layoutParams = pause

        val timer = binding.timerText.layoutParams as FrameLayout.LayoutParams
        timer.topMargin = if (portrait) dp(115) else dp(62)
        binding.timerText.layoutParams = timer

        listOf(binding.statusText, binding.timerText, binding.qualityButton,
            binding.fpsButton, binding.lockButton, binding.zoom06Button,
            binding.zoom1Button, binding.zoom2Button, binding.zoom3Button,
            binding.modeLabel).forEach { view ->
                view.animate().cancel()
                view.rotation = 0f
            }
    }

    private fun updateStatusText() {
        if (showDiagnostics) {
            val gpu = horizonEffect?.processor?.diagnostics() ?: "P0 V0 F0"
            binding.statusText.text = "v1.0 " + gpu +
                (if (nativeEisActive) " EIS" else " GPU") + " / " +
                String.format(Locale.US, "%.0f", horizonState.correctionDegrees()) + "°"
        } else {
            binding.statusText.text = when {
                recording != null -> "● REC"
                horizonState.mode() == StabilizationMode.HORIZONTAL_LOCK -> "H LOCK 360°"
                horizonState.mode() == StabilizationMode.STEADY -> "SUPER STEADY"
                else -> "VIDEO"
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun haptic() {
        binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Each entry is backed by the selected pipeline, not a cosmetic toggle. */
    private fun showStabilizationOptions() {
        if (recording != null) return
        PopupMenu(this, binding.lockButton).apply {
            menu.add(0, 1, 0, "Desligado")
            menu.add(0, 2, 1, "Super Steady")
            menu.add(0, 3, 2, "Horizontal Lock 360°")
            setOnMenuItemClickListener { item ->
                val requested = when (item.itemId) {
                    1 -> StabilizationMode.OFF
                    2 -> StabilizationMode.STEADY
                    else -> StabilizationMode.HORIZONTAL_LOCK
                }
                if (requested == StabilizationMode.STEADY && !eisSupported) {
                    toast("Super Steady nativo não disponível nesta câmera.")
                    return@setOnMenuItemClickListener true
                }
                if (horizonState.mode() != requested) {
                    if (requested == StabilizationMode.STEADY) {
                        requestedQuality = Quality.FHD
                        requestedFps = 30
                        binding.qualityButton.text = "FHD"
                        binding.fpsButton.text = "30"
                    }
                    horizonState.setMode(requested)
                    if (requested != StabilizationMode.HORIZONTAL_LOCK && usingWide) {
                        usingWide = false
                        selectedZoom = 1f
                    } else if (requested == StabilizationMode.HORIZONTAL_LOCK &&
                        wideLens != null && selectedZoom <= 1f) {
                        usingWide = true
                        selectedZoom = 0.6f
                    }
                    pulseLockButton()
                    haptic()
                    binding.lockButton.text = when (requested) {
                        StabilizationMode.OFF -> "OFF"
                        StabilizationMode.STEADY -> "STEADY"
                        StabilizationMode.HORIZONTAL_LOCK -> "360°"
                    }
                    binding.lockButton.setTextColor(
                        if (requested == StabilizationMode.OFF) Color.WHITE
                        else Color.rgb(255, 216, 74)
                    )
                    restartCamera()
                }
                true
            }
            show()
        }
    }

    private fun showQuickControls() {
        PopupMenu(this, binding.quickControlsButton).apply {
            menu.add(0, 101, 0,
                if (binding.gridView.visibility == View.VISIBLE) "Ocultar grade" else "Mostrar grade")
            menu.add(0, 102, 1,
                if (soundEnabled) "Áudio: ligado" else "Áudio: desligado")
            menu.add(0, 103, 2,
                if (showDiagnostics) "Ocultar diagnóstico" else "Exibir diagnóstico")
            menu.add(0, 104, 3, "Lentes e capacidades")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    101 -> {
                        val show = binding.gridView.visibility != View.VISIBLE
                        binding.gridView.visibility = if (show) View.VISIBLE else View.GONE
                        prefs.edit().putBoolean("grid", show).apply()
                    }
                    102 -> {
                        soundEnabled = !soundEnabled
                        prefs.edit().putBoolean("sound", soundEnabled).apply()
                    }
                    103 -> {
                        showDiagnostics = !showDiagnostics
                        updateStatusText()
                    }
                    104 -> {
                        val cap = capabilities
                        if (cap != null) {
                            val fovs = cap.availableLenses.joinToString { lens ->
                                val fov = lens.approximateHorizontalFovDegrees
                                lens.id + ":" + (if (fov == null) "?" else "%.0f°".format(fov))
                            }
                            toast("Camera2: ${cap.cameraId} • FOV: ${fovs.ifBlank { "indisponível" }}")
                        } else toast("A câmera ainda não abriu.")
                    }
                }
                haptic()
                true
            }
            show()
        }
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) {
            toast("Esta lente não oferece lanterna.")
            return
        }
        torchEnabled = !torchEnabled
        cam.cameraControl.enableTorch(torchEnabled)
        binding.flashButton.setIconTint(
            ColorStateList.valueOf(
                if (torchEnabled) Color.rgb(255, 216, 74) else Color.WHITE
            )
        )
        haptic()
    }

    /**
     * Maps the presentation-only center crop and canonical GL correction
     * approximately back into the logical CameraX surface.
     */
    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val vw = binding.previewView.width.toFloat().coerceAtLeast(1f)
        val vh = binding.previewView.height.toFloat().coerceAtLeast(1f)
        val videoAspect = 16.0 / 9.0
        val viewAspect = vw / vh
        var cx = 2.0 * x / vw - 1.0
        var cy = 1.0 - 2.0 * y / vh
        if (videoAspect > viewAspect) cx *= viewAspect / videoAspect
        else cy *= videoAspect / viewAspect

        val model = horizonState.frameGeometry(android.os.SystemClock.elapsedRealtimeNanos())
        val inverse = AngleMath.correctionMatrix(
            model.rotationDegrees.toDouble(),
            model.cropScale.toDouble(),
            videoAspect
        ).inverse()
        val sensorPoint = inverse.apply(cx, cy)
        val nx = (0.5 + sensorPoint.first / 2.0).coerceIn(0.05, 0.95).toFloat()
        val ny = (0.5 - sensorPoint.second / 2.0).coerceIn(0.05, 0.95).toFloat()
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(nx, ny)
        val action = FocusMeteringAction.Builder(
            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)

        val indicator = binding.focusIndicator
        indicator.animate().cancel()
        indicator.translationX = x - vw / 2f
        indicator.translationY = y - vh / 2f
        indicator.alpha = 1f
        indicator.visibility = View.VISIBLE
        indicator.postDelayed({
            indicator.animate().alpha(0f).setDuration(250).withEndAction {
                indicator.visibility = View.GONE
            }.start()
        }, 900)

        val exposure = cam.cameraInfo.exposureState
        val range = exposure.exposureCompensationRange
        if (range.upper > range.lower && exposure.isExposureCompensationSupported) {
            binding.exposureSlider.max = range.upper - range.lower
            binding.exposureSlider.progress = exposure.exposureCompensationIndex - range.lower
            binding.exposureSlider.visibility = View.VISIBLE
        }
        haptic()
    }

    private fun togglePause() {
        val active = recording ?: return
        if (paused) active.resume() else active.pause()
        paused = !paused
        binding.pauseButton.text = if (paused) "▶" else "Ⅱ"
        binding.pauseButton.contentDescription =
            if (paused) "Retomar gravação" else "Pausar gravação"
        haptic()
    }

    private fun showLastVideo(uri: Uri) {
        galleryExecutor.execute {
            val bitmap = runCatching {
                contentResolver.loadThumbnail(uri, Size(144, 144), null)
            }.getOrNull()
            if (bitmap != null) runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    binding.galleryThumbnail.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun openLastVideo() {
        val uri = lastVideoUri ?: run {
            toast("Nenhum vídeo gravado ainda.")
            return
        }
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        }.onFailure { toast("Não foi possível abrir o vídeo.") }
    }

    private fun verifySavedVideo(uri: Uri) {
        if (uri == Uri.EMPTY) return
        lastVideoUri = uri
        prefs.edit().putString("lastVideo", uri.toString()).apply()
        showLastVideo(uri)
        galleryExecutor.execute {
            val result = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, uri)
                    val width = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                    )?.toIntOrNull() ?: 0
                    val height = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                    )?.toIntOrNull() ?: 0
                    val rotation = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                    )?.toIntOrNull() ?: 0
                    Triple(width, height, rotation)
                } finally {
                    retriever.release()
                }
            }.getOrNull()
            result?.let { (w,h,r) ->
                val effectiveWidth = if (r % 180 == 0) w else h
                val effectiveHeight = if (r % 180 == 0) h else w
                android.util.Log.i(
                    "HorizonCamMetadata",
                    "encoded=${w}x${h} rotation=${r} display=${effectiveWidth}x${effectiveHeight}"
                )
                if (horizonState.isEnabled() && effectiveWidth < effectiveHeight) {
                    runOnUiThread {
                        toast("Aviso: MP4 saiu vertical. Diagnóstico registrado no Logcat.")
                    }
                }
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
