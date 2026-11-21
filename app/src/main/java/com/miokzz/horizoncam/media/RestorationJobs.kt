package com.miokzz.horizoncam.media

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.miokzz.horizoncam.ai.HorizonAi
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A single local restoration may continue while the camera Activity is in the foreground. */
object RestorationJobs {
    data class State(
        val input: AiPhotoFiles.Input? = null,
        val running: Boolean = false,
        val completed: Int = 0,
        val total: Int = 0,
        val message: String = "",
        val saved: Uri? = null
    )
    private val updates = MutableLiveData(State())
    val states: LiveData<State> get() = updates
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean()
    private val cancelled = AtomicBoolean()

    fun start(context: Context, input: AiPhotoFiles.Input) {
        if (!busy.compareAndSet(false, true)) return
        val app = context.applicationContext
        cancelled.set(false)
        updates.value = State(input, running = true, message = "Lendo pixels da foto…")
        worker.execute {
            val terminal = runCatching {
                val original = AiPhotoFiles.read(app, input)
                try {
                    val result = HorizonAi.infer(app, original, onProgress = { progress ->
                        updates.postValue(State(input, true, progress.completedTiles, progress.totalTiles, "Restaurando"))
                    }, isCancelled = { cancelled.get() })
                    try {
                        if (cancelled.get()) throw CancellationException()
                        updates.postValue(State(input, running = true, message = "Gravando JPEG…"))
                        val size = "${original.width}×${original.height} → ${result.bitmap.width}×${result.bitmap.height}"
                        val uri = AiPhotoFiles.save(app, input.uri, result.bitmap, "HC_AI",
                            "Horizon AI; pretrained ESRGAN; estimated details; input/output $size; zoom=${input.zoom ?: "unknown"}")
                        State(input, message = "$size · ${result.metrics.elapsedMs / 1000}s · detalhes estimados pelo modelo", saved = uri)
                    } finally { result.bitmap.recycle() }
                } finally { original.recycle() }
            }.getOrElse { error ->
                if (cancelled.get() || error is CancellationException) State(input, message = "Processamento cancelado.")
                else {
                    Log.e("HorizonAI", "Falha na restauração local", error)
                    State(input, message = "Falha na restauração: ${error.localizedMessage ?: "imagem indisponível"}")
                }
            }
            androidx.core.content.ContextCompat.getMainExecutor(app).execute {
                busy.set(false)
                updates.value = terminal
            }
        }
    }

    fun cancel() { cancelled.set(true) }
}
