package com.miokzz.horizoncam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
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
                    selectedZoom = target
                    updateZoomUi(target)
                    return true
                }
            }
        )

        binding.previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            true
        }

        binding.lockButton.setOnClickListener {
            val active = horizonState.toggleEnabled()
            binding.lockButton.text = if (active) "H" else "OFF"
            binding.lockButton.setTextColor(
                if (active) Color.rgb(255, 216, 74) else Color.WHITE
            )
            pulseLockButton()
        }

        binding.lockButton.setOnLongClickListener {
            val direction = horizonState.toggleDirection()
            Toast.makeText(
                this,
                if (direction > 0f) "Orientação GL normal" else "Orientação GL invertida",
                Toast.LENGTH_SHORT
            ).show()
            true
        }

        binding.qualityButton.setOnClickListener {
            if (recording != null) return@setOnClickListener
            requestedQuality = if (requestedQuality == Quality.UHD) Quality.FHD else Quality.UHD
            binding.qualityButton.text = if (requestedQuality == Quality.UHD) "UHD" else "FHD"
            restartCamera()
        }

        binding.fpsButton.setOnClickListener {
            if (recording != null) return@setOnClickListener
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

        val halCanStabilize = runCatching {
            val info = provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
            Preview.getPreviewCapabilities(info).isStabilizationSupported &&
                Recorder.getVideoCapabilities(info).isStabilizationSupported
        }.getOrDefault(false)
        val eis = allowEis ?: (
            halCanStabilize && requestedQuality == Quality.FHD && fps == 30
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
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group)
            val source = runCatching {
                Camera2CameraInfo.from(camera!!.cameraInfo)
                    .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            }.getOrNull()
            horizonState.setCameraRealtimeTimestamp(
                source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            )
            nativeEisActive = eis
            cameraStarting = false
            updateStatusText()
            updateZoomAvailability()
            updateZoomUi(selectedZoom)
        } catch (t: Throwable) {
            effect.close()

            if (eis) {
                // Some devices report HAL stabilization but cannot combine it
                // with an external GPU effect. Rebind without HAL EIS.
                nativeEisActive = false
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
        val state = cam.cameraInfo.zoomState.value ?: return
        val value = requested.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        selectedZoom = value
        cam.cameraControl.setZoomRatio(value)
        updateZoomUi(value)

        if (requested < state.minZoomRatio - 0.01f) {
            Toast.makeText(
                this,
                "A lente atual começa em ${"%.1f".format(state.minZoomRatio)}×",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateZoomAvailability() {
        val state = camera?.cameraInfo?.zoomState?.value ?: return
        binding.zoom06Button.isEnabled = state.minZoomRatio <= 0.61f
        binding.zoom06Button.alpha = if (binding.zoom06Button.isEnabled) 1f else 0.35f
        binding.zoom2Button.isEnabled = state.maxZoomRatio >= 2f
        binding.zoom2Button.alpha = if (binding.zoom2Button.isEnabled) 1f else 0.35f
        binding.zoom3Button.isEnabled = state.maxZoomRatio >= 3f
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
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
                        Toast.makeText(
                            this,
                            "Vídeo salvo em Movies/HorizonCam",
                            Toast.LENGTH_SHORT
                        ).show()
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
        updateStatusText()
    }

    private fun setSettingsEnabled(enabled: Boolean) {
        binding.qualityButton.isEnabled = enabled
        binding.fpsButton.isEnabled = enabled
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

        // Camera streams remain at Surface.ROTATION_90 while UI rotates.
        // Rebinding or calling setTargetRotation here would change the
        // output geometry and produce portrait MP4s / jumping previews.
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
            binding.statusText.text = if (recording != null) "● REC"
                else if (horizonState.isEnabled() && !horizonState.isLandscapePose())
                    "GIRE ↻ 16:9"
                else "H LOCK"
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
