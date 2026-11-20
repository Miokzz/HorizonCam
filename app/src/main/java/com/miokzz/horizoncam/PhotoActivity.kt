package com.miokzz.horizoncam

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.miokzz.horizoncam.camera.PublicLensDiscovery
import com.miokzz.horizoncam.databinding.ActivityPhotoBinding
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Dedicated still-photo pipeline.
 *
 * Video uses its own GPU/Recorder effects in MainActivity. Keeping the photo
 * session separate prevents the photo use case from breaking video binding.
 * CameraX does not guarantee that logical 3x zoom selects the physical tele lens.
 */
class PhotoActivity : AppCompatActivity() {
    private enum class Lens(val label: String) {
        ULTRA("0,6×"), MAIN("1×"), TELE("3×")
    }

    private lateinit var binding: ActivityPhotoBinding
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var photoCapture: ImageCapture? = null
    private var ultrawide: PublicLensDiscovery.WideLens? = null
    private var telephoto: PublicLensDiscovery.TeleLens? = null
    private var activeLens = Lens.MAIN
    private var usingDedicatedCamera = false
    private var capturing = false
    private var lastPhoto: Uri? = null

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openCamera() else {
            toast("Permissão de câmera necessária.")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lastPhoto = getSharedPreferences("horizoncam", MODE_PRIVATE)
            .getString("lastPhoto", null)?.let(Uri::parse)
        binding.galleryButton.isEnabled = lastPhoto != null
        binding.backToVideoButton.setOnClickListener { finish() }
        binding.shutterButton.setOnClickListener { takePhoto() }
        binding.galleryButton.setOnClickListener { openLastPhoto() }

        binding.lens06Button.setOnClickListener { changeLens(Lens.ULTRA) }
        binding.lens1Button.setOnClickListener { changeLens(Lens.MAIN) }
        binding.lens3Button.setOnClickListener { changeLens(Lens.TELE) }
        selectUi()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            openCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                provider = future.get()
                val p = provider ?: return@addListener
                ultrawide = runCatching {
                    PublicLensDiscovery.findAccessibleUltrawide(this, p)
                }.getOrNull()
                telephoto = runCatching {
                    PublicLensDiscovery.findAccessibleTelephoto(this, p)
                }.getOrNull()
                bindPhotoCamera()
            } catch (e: Exception) {
                toast("Falha ao iniciar câmera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun mainZoomRange(): Pair<Float, Float> {
        val state = runCatching {
            provider?.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)?.zoomState?.value
        }.getOrNull()
        return (state?.minZoomRatio ?: 1f) to (state?.maxZoomRatio ?: 1f)
    }

    private fun changeLens(next: Lens) {
        if (capturing || provider == null) return
        val (minZoom, maxZoom) = mainZoomRange()
        if (next == Lens.ULTRA && ultrawide == null && minZoom > 0.61f) {
            toast("0,6× não está acessível à câmera de terceiros.")
            return
        }
        if (next == Lens.TELE && telephoto == null && maxZoom < 3f) {
            toast("3× não está disponível pela API pública.")
            return
        }
        activeLens = next
        bindPhotoCamera()
    }

    private fun bindPhotoCamera() {
        val p = provider ?: return
        val selected = when (activeLens) {
            Lens.ULTRA -> ultrawide?.selector ?: CameraSelector.DEFAULT_BACK_CAMERA
            Lens.MAIN -> CameraSelector.DEFAULT_BACK_CAMERA
            Lens.TELE -> telephoto?.selector ?: CameraSelector.DEFAULT_BACK_CAMERA
        }

        val succeeded = runCatching { bindSession(p, selected) }.isSuccess
        if (!succeeded) {
            // A separately listed physical camera may reject a still capture
            // session. Fall back to the logical back camera when possible.
            if (selected !== CameraSelector.DEFAULT_BACK_CAMERA) {
                toast("Lente física recusou a sessão. Tentando câmera lógica.")
                if (runCatching { bindSession(p, CameraSelector.DEFAULT_BACK_CAMERA) }.isFailure) {
                    toast("Não foi possível abrir a câmera.")
                    return
                }
            } else {
                toast("Não foi possível abrir a câmera.")
                return
            }
        }

        val zoom = camera?.cameraInfo?.zoomState?.value
        if (zoom != null) {
            val target = if (usingDedicatedCamera) 1f else when (activeLens) {
                Lens.ULTRA -> 0.6f
                Lens.MAIN -> 1f
                Lens.TELE -> 3f
            }
            if (target in zoom.minZoomRatio..zoom.maxZoomRatio) {
                camera?.cameraControl?.setZoomRatio(target)
            } else if (activeLens != Lens.MAIN) {
                toast("Ampliação ${activeLens.label} não suportada nesta sessão.")
                activeLens = Lens.MAIN
                camera?.cameraControl?.setZoomRatio(1f)
            }
        }
        val (minZoom, maxZoom) = mainZoomRange()
        binding.lens06Button.isEnabled = ultrawide != null || minZoom <= 0.61f
        binding.lens3Button.isEnabled = telephoto != null || maxZoom >= 3f
        binding.lens06Button.alpha = if (binding.lens06Button.isEnabled) 1f else 0.35f
        binding.lens3Button.alpha = if (binding.lens3Button.isEnabled) 1f else 0.35f
        selectUi()
    }

    private fun bindSession(p: ProcessCameraProvider, selector: CameraSelector) {
        p.unbindAll()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.photoPreview.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
        val bound = p.bindToLifecycle(this, selector, preview, capture)
        camera = bound
        photoCapture = capture
        usingDedicatedCamera = selector !== CameraSelector.DEFAULT_BACK_CAMERA
    }

    private fun selectUi() {
        listOf(
            Lens.ULTRA to binding.lens06Button,
            Lens.MAIN to binding.lens1Button,
            Lens.TELE to binding.lens3Button
        ).forEach { (lens, button) ->
            val selected = activeLens == lens
            button.backgroundTintList = ColorStateList.valueOf(
                if (selected) Color.WHITE else Color.argb(175, 24, 24, 24)
            )
            button.setTextColor(if (selected) Color.BLACK else Color.WHITE)
        }
        binding.lensStatusText.text = when (activeLens) {
            Lens.ULTRA -> if (usingDedicatedCamera) "ULTRAWIDE • 0,6×"
                else "0,6× • CÂMERA LÓGICA"
            Lens.MAIN -> "PRINCIPAL • 1×"
            Lens.TELE -> if (usingDedicatedCamera) "TELEOBJETIVA • ZOOM ÓPTICO"
                else "3× • SELEÇÃO AUTOMÁTICA DE LENTE"
        }
    }

    private fun takePhoto() {
        val capture = photoCapture ?: return
        if (capturing) return
        capturing = true
        binding.shutterButton.isEnabled = false
        capture.targetRotation = binding.photoPreview.display?.rotation ?: Surface.ROTATION_0

        val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
            .format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "HorizonCam_${name}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HorizonCam")
        }
        val output = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).build()

        capture.takePicture(
            output,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    capturing = false
                    binding.shutterButton.isEnabled = true
                    lastPhoto = results.savedUri
                    getSharedPreferences("horizoncam", MODE_PRIVATE)
                        .edit().putString("lastPhoto", lastPhoto?.toString()).apply()
                    binding.galleryButton.isEnabled = lastPhoto != null
                    toast("Foto salva em Pictures/HorizonCam")
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    binding.shutterButton.isEnabled = true
                    toast("Erro ao salvar foto: ${exception.message}")
                }
            }
        )
    }

    private fun openLastPhoto() {
        val uri = lastPhoto ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { toast("Nenhum visualizador de fotos encontrado.") }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
