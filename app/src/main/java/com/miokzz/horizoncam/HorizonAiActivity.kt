package com.miokzz.horizoncam

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.miokzz.horizoncam.databinding.ActivityHorizonAiBinding
import com.miokzz.horizoncam.media.AiPhotoFiles
import com.miokzz.horizoncam.media.RestorationJobs
import com.miokzz.horizoncam.ui.PatchGuide
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class HorizonAiActivity : AppCompatActivity() {
    private lateinit var ui: ActivityHorizonAiBinding
    private val reader = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var sourceGeneration = 0
    private var source: Uri? = null
    private var zoom: Float? = null
    private var x = 0.5f
    private var y = 0.5f
    private var showingScene = true
    private var after: Uri? = null
    private var comparison: AiPhotoFiles.Input? = null
    private var fullPreview: Bitmap? = null
    private var imageInfo = ""
    private val guide by lazy { PatchGuide(ui.image) }
    private var selectedPatch = false
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            select(uri, null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityHorizonAiBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ViewCompat.setOnApplyWindowInsetsListener(ui.root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        ui.backToCamera.setOnClickListener { finish() }
        ui.chooseImage.setOnClickListener { picker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }
        ui.showBefore.setOnClickListener { showProcessed(false) }
        ui.showAfter.setOnClickListener { showProcessed(true) }
        ui.gallery.setOnClickListener {
            after?.let { runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(it, "image/jpeg")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { ui.description.text = "Nenhum visualizador de JPEG disponível." } }
        }
        ui.area.setOnCheckedChangeListener { _, id ->
            selectedPatch = id == R.id.patch
            if (source != null) resetComparison()
        }
        ui.image.setOnClickListener {
            if (selectedPatch && source != null && RestorationJobs.states.value?.running != true) {
                resetComparison()
                ui.description.text = "Área de detalhe em ${(100*x).roundToInt()}%, ${(100*y).roundToInt()}% da foto."
            }
        }
        ui.image.setOnTouchListener { view, event ->
            if (!selectedPatch || !showingScene || RestorationJobs.states.value?.running == true) return@setOnTouchListener false
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val bmp = fullPreview ?: return@setOnTouchListener true
                val inverse = Matrix()
                if (ui.image.imageMatrix.invert(inverse)) {
                    val coords = floatArrayOf(event.x, event.y)
                    inverse.mapPoints(coords)
                    x = (coords[0] / bmp.width).coerceIn(0f, 1f)
                    y = (coords[1] / bmp.height).coerceIn(0f, 1f)
                    view.performClick()
                }
            }
            true
        }
        ui.process.setOnClickListener { source?.let { RestorationJobs.start(this, AiPhotoFiles.Input(it, selectedPatch, x, y, zoom)) } }
        ui.cancelJob.setOnClickListener { RestorationJobs.cancel() }
        val running = RestorationJobs.states.value
        val previous = if (running?.running == true) running.input else null
        val uri = previous?.uri ?: savedInstanceState?.getString("source")?.let(Uri::parse) ?: intent.data
        zoom = previous?.zoom ?: savedInstanceState?.getFloat("zoom")?.takeIf { it > 0 } ?: intent.getFloatExtra("zoom", -1f).takeIf { it > 0 }
        selectedPatch = previous?.detail ?: savedInstanceState?.getBoolean("detail") ?: false
        ui.area.check(if (selectedPatch) R.id.patch else R.id.full)
        if (uri != null) {
            select(uri, zoom)
            x = previous?.x ?: savedInstanceState?.getFloat("x", 0.5f) ?: 0.5f
            y = previous?.y ?: savedInstanceState?.getFloat("y", 0.5f) ?: 0.5f
        } else ui.description.text = "Escolha uma foto para começar."
        RestorationJobs.states.observe(this) { state ->
            val matches = state.input?.uri == source
            val enabled = !state.running
            ui.chooseImage.isEnabled = enabled
            ui.full.isEnabled = enabled
            ui.patch.isEnabled = enabled
            ui.process.isEnabled = enabled && source != null && fullPreview != null
            ui.progress.isVisible = state.running
            ui.cancelJob.isVisible = state.running
            if (state.running) {
                ui.progress.isIndeterminate = state.total == 0
                if (state.total > 0) { ui.progress.max = state.total; ui.progress.progress = state.completed }
                ui.description.text = if (state.total > 0) "${state.message} · ${state.completed}/${state.total} blocos" else state.message
            } else if (matches && state.message.isNotEmpty()) ui.description.text = state.message
            if (matches && state.saved != null && after != state.saved) {
                comparison = state.input
                after = state.saved
                showProcessed(true)
            }
            comparisonButtons()
        }
        comparisonButtons()
    }

    private fun select(uri: Uri, cameraZoom: Float?) {
        source = uri
        zoom = cameraZoom
        x = 0.5f
        y = 0.5f
        fullPreview = null
        resetComparison()
        ui.process.isEnabled = false
        ui.description.text = "Lendo foto…"
        val token = ++sourceGeneration
        reader.execute {
            val result = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                Triple(AiPhotoFiles.preview(this, uri), "${bounds.outWidth} × ${bounds.outHeight} px", maxOf(bounds.outWidth, bounds.outHeight))
            }
            runOnUiThread {
                if (!isDestroyed && token == sourceGeneration) result.onSuccess { (bitmap, dimensions, longEdge) ->
                    guide.sourceLongEdge = longEdge
                    fullPreview = bitmap
                    imageInfo = dimensions + (zoom?.let { String.format(java.util.Locale.getDefault(), " · zoom %.2f×", it) } ?: " · zoom desconhecido")
                    if (showingScene) { ui.image.setImageBitmap(bitmap); updateGuide() }
                    if (RestorationJobs.states.value?.running != true) {
                        ui.process.isEnabled = true
                        if (showingScene) describeInput()
                    }
                }.onFailure { ui.description.text = "Não foi possível abrir a imagem." }
            }
        }
    }

    private fun describeInput() {
        ui.description.text = imageInfo + "\n" + if (selectedPatch) "Toque na foto para escolher um detalhe. O centro já está selecionado."
            else "O modelo estima detalhes e salva um novo JPEG. A foto original fica na galeria."
    }

    private fun resetComparison() {
        generation++
        showingScene = true
        after = null
        comparison = null
        ui.image.setImageBitmap(fullPreview)
        updateGuide()
        describeInput()
        comparisonButtons()
    }
    private fun updateGuide() {
        guide.centerX = x
        guide.centerY = y
        ui.image.foreground = if (selectedPatch && showingScene && fullPreview != null) guide else null
        ui.image.invalidate()
    }

    private fun comparisonButtons() {
        val available = after != null && RestorationJobs.states.value?.running != true
        ui.showBefore.isVisible = available
        ui.showAfter.isVisible = available
        ui.gallery.isVisible = available
    }

    private fun showProcessed(processed: Boolean) {
        val input = comparison ?: return
        val saved = after ?: return
        val token = ++generation
        showingScene = false
        updateGuide()
        reader.execute {
            val result = runCatching { if (processed) AiPhotoFiles.preview(this, saved, 1400) else AiPhotoFiles.read(this, input) }
            runOnUiThread {
                if (!isDestroyed && token == generation) result.onSuccess {
                    ui.image.setImageBitmap(it)
                    ui.showBefore.isSelected = !processed
                    ui.showAfter.isSelected = processed
                }.onFailure { ui.description.text = "Esta mídia está indisponível." }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("source", source?.toString())
        outState.putFloat("zoom", zoom ?: -1f)
        outState.putBoolean("detail", selectedPatch)
        outState.putFloat("x", x)
        outState.putFloat("y", y)
    }

    override fun onDestroy() { reader.shutdownNow(); super.onDestroy() }
}
