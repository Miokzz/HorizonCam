package com.miokzz.horizoncam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import android.util.Range
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.media3.effect.Media3Effect
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.miokzz.horizoncam.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var effectExecutor: ExecutorService
    private lateinit var gravitySensor: GravityRollSensor

    private val horizonState = HorizonState()
    private var media3Effect: Media3Effect? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var requestedQuality = Quality.UHD
    private var requestedFps = 30
    private var cameraStarting = false

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

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        effectExecutor = Executors.newSingleThreadExecutor()
        gravitySensor = GravityRollSensor(this) { roll ->
            horizonState.updateRoll(roll)
            runOnUiThread { updateHud() }
        }

        binding.previewView.scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER

        binding.lockButton.setOnClickListener {
            horizonState.recalibrate()
            horizonState.setEnabled(true)
            updateHud()
            Toast.makeText(this, "Horizonte recalibrado", Toast.LENGTH_SHORT).show()
        }

        binding.qualityButton.setOnClickListener {
            if (recording != null) return@setOnClickListener
            requestedQuality = if (requestedQuality == Quality.UHD) Quality.FHD else Quality.UHD
            binding.qualityButton.text = if (requestedQuality == Quality.UHD) "4K" else "1080p"
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

        requestPermissionsOrStart()
    }

    override fun onResume() {
        super.onResume()
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
        media3Effect?.close()
        cameraExecutor.shutdown()
        effectExecutor.shutdown()
        super.onDestroy()
    }

    private fun requestPermissionsOrStart() {
        val cameraOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (cameraOk && audioOk) {
            startCamera()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun startCamera() {
        if (cameraStarting || isFinishing || isDestroyed) return
        cameraStarting = true
        binding.statusText.text = "HORIZON v0.3 • STARTING"

        val providerFuture: ListenableFuture<ProcessCameraProvider> = ProcessCameraProvider.getInstance(this)
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
        media3Effect?.close()
        media3Effect = null
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

        val effect = Media3Effect(
            this,
            CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
            effectExecutor
        ) { error ->
            runOnUiThread {
                binding.statusText.text = "GPU EFFECT ERROR"
                Toast.makeText(this, "Efeito de horizonte falhou: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
        effect.setEffects(listOf<Effect>(HorizonMatrixEffect(horizonState)))

        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(capture)
            .addEffect(effect)
            .build()

        try {
            media3Effect?.close()
            media3Effect = effect
            videoCapture = capture
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group)
            cameraStarting = false
            binding.statusText.text = "HORIZON v0.3 • READY"
            updateZoomAvailability()
            updateHud()
        } catch (t: Throwable) {
            effect.close()
            if (fps == 60) {
                requestedFps = 30
                binding.fpsButton.text = "30"
                Toast.makeText(this, "60 fps não fechou com esse modo; caindo para 30 fps.", Toast.LENGTH_SHORT).show()
                bindCameraWithFallback(30)
            } else {
                cameraStarting = false
                binding.statusText.text = "BIND ERROR"
                Toast.makeText(this, "Combinação não suportada: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setZoom(requested: Float) {
        val cam = camera ?: return
        val state = cam.cameraInfo.zoomState.value ?: return
        val value = requested.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        cam.cameraControl.setZoomRatio(value)
        if (requested < state.minZoomRatio - 0.01f) {
            Toast.makeText(this, "Essa câmera expõe mínimo ${"%.1f".format(state.minZoomRatio)}×", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateZoomAvailability() {
        val state = camera?.cameraInfo?.zoomState?.value ?: return
        binding.zoom06Button.isEnabled = state.minZoomRatio <= 0.61f
        binding.zoom2Button.isEnabled = state.maxZoomRatio >= 2f
        binding.zoom3Button.isEnabled = state.maxZoomRatio >= 3f
    }

    private fun startRecording() {
        val capture = videoCapture ?: return

        horizonState.recalibrate()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
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
        val withAudio = if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending.withAudioEnabled()
        } else pending

        setSettingsEnabled(false)
        recording = withAudio.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    binding.recordButton.text = "STOP"
                    binding.statusText.text = "● RECORDING"
                }

                is VideoRecordEvent.Status -> {
                    val seconds = event.recordingStats.recordedDurationNanos / 1_000_000_000L
                    binding.statusText.text = "● REC ${seconds}s"
                }

                is VideoRecordEvent.Finalize -> {
                    recording?.close()
                    recording = null
                    binding.recordButton.text = "REC"
                    setSettingsEnabled(true)
                    if (event.hasError()) {
                        binding.statusText.text = "RECORD ERROR"
                        Toast.makeText(this, "Erro ao gravar: ${event.error}", Toast.LENGTH_LONG).show()
                    } else {
                        binding.statusText.text = "HORIZON v0.3 • SAVED"
                        Toast.makeText(this, "Salvo em Movies/HorizonCam", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun stopRecording() {
        recording?.stop()
    }

    private fun setSettingsEnabled(enabled: Boolean) {
        binding.qualityButton.isEnabled = enabled
        binding.fpsButton.isEnabled = enabled
    }

    private fun updateHud() {
        val correction = horizonState.correctionDegrees()
        val crop = horizonState.safeCropScale()
        binding.angleText.text = "roll ${"%+.1f".format(correction)}°  crop ${"%.2f".format(crop)}×"
    }
}
