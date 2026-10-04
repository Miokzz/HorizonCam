package com.miokzz.horizoncam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.util.Range
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
    private var recording: Recording? = null

    private var requestedQuality = Quality.FHD
    private var requestedFps = 30
    private var cameraStarting = false

    private var hudReferenceRoll: Float? = null
    private var lastHudRotation = 0f
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

        binding.previewView.scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
        binding.qualityButton.text = "FHD"
        binding.fpsButton.text = "30"
        binding.statusText.text = "HORIZON LOCK"

        gravitySensor = GravityRollSensor(this) { roll ->
            horizonState.updateRoll(roll)
            runOnUiThread {
                updateRotatingHud(roll)
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
            horizonState.recalibrate()
            horizonState.setEnabled(true)
            pulseLockButton()
            Toast.makeText(this, "Horizon Lock recalibrado", Toast.LENGTH_SHORT).show()
        }

        binding.lockButton.setOnLongClickListener {
            val direction = horizonState.toggleDirection()
            Toast.makeText(
                this,
                if (direction > 0f) "Compensação normal" else "Compensação invertida",
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
        cameraProvider?.unbindAll()
        horizonEffect?.close()
        horizonEffect = null
        cameraStarting = false
        startCamera()
    }

    private fun bindCameraWithFallback(fps: Int) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val preview = Preview.Builder()
            .setTargetRotation(binding.previewView.display.rotation)
            .build()
            .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

        val qualitySelector = QualitySelector.from(
            requestedQuality,
            FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)
        )

        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()

        val capture = VideoCapture.Builder(recorder)
            .setTargetRotation(binding.previewView.display.rotation)
            .setTargetFrameRate(Range(fps, fps))
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

        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(capture)
            .addEffect(effect)
            .build()

        try {
            horizonEffect?.close()
            horizonEffect = effect
            videoCapture = capture
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group)
            cameraStarting = false
            binding.statusText.text = "HORIZON LOCK"
            updateZoomAvailability()
            updateZoomUi(selectedZoom)
        } catch (t: Throwable) {
            effect.close()

            if (fps == 60) {
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
        binding.statusText.text = if (isRecording) "HORIZON LOCK • REC" else "HORIZON LOCK"
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

    private fun updateRotatingHud(rawRoll: Float) {
        if (hudReferenceRoll == null) {
            hudReferenceRoll = rawRoll
            lastHudRotation = 0f
        }

        var target = wrapDegrees((hudReferenceRoll ?: rawRoll) - rawRoll)

        while (target - lastHudRotation > 180f) target -= 360f
        while (target - lastHudRotation < -180f) target += 360f

        lastHudRotation = target

        val rotatingViews = listOf(
            binding.statusText,
            binding.timerText,
            binding.qualityButton,
            binding.fpsButton,
            binding.lockButton,
            binding.zoom06Button,
            binding.zoom1Button,
            binding.zoom2Button,
            binding.zoom3Button,
            binding.modeLabel
        )

        rotatingViews.forEach { view ->
            view.rotation = target
        }
    }

    private fun wrapDegrees(value: Float): Float {
        var v = value
        while (v > 180f) v -= 360f
        while (v < -180f) v += 360f
        return v
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
